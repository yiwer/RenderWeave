package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.ExternalTransferConfirmation;
import cn.hbads.renderweave.inference.admission.ExternalTransferNotice;
import cn.hbads.renderweave.inference.admission.GatewayAssertionAuthority;
import cn.hbads.renderweave.inference.admission.GatewayRequestIdentity;
import cn.hbads.renderweave.inference.admission.InputProvenance;
import cn.hbads.renderweave.inference.admission.LiveAdmissionConfiguration;
import cn.hbads.renderweave.inference.admission.LiveAdmissionProblem;
import cn.hbads.renderweave.inference.admission.LiveInputManifest;
import cn.hbads.renderweave.inference.admission.NewLiveInferenceRun;
import cn.hbads.renderweave.inference.admission.SensitivityClass;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.input.NormalizedInput;
import cn.hbads.renderweave.inference.input.NormalizedInputReference;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest
class PostgresLiveAdmissionStoreTest {
    private static final Instant T0 = Instant.parse("2026-08-18T08:00:00Z");
    private static final String IDEMPOTENCY_KEY = "live-idempotency-001";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PostgresLiveAdmissionStore store;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clear() {
        jdbcClient.sql("truncate table external_transfer_notice, inference_run, inference_artifact cascade")
                .update();
    }

    @Test
    void responseLossReplayReturnsOriginalRunAndConfirmationWithFreshGatewayIdentity() {
        var first = command(
                IDEMPOTENCY_KEY,
                uuid("11111111-1111-4111-8111-111111111111"),
                uuid("22222222-2222-4222-8222-222222222222"),
                "1".repeat(64), "request-1", "jti-1", T0, configuration()
        );
        var created = store.admit(first);
        var replay = command(
                IDEMPOTENCY_KEY,
                uuid("33333333-3333-4333-8333-333333333333"),
                uuid("44444444-4444-4444-8444-444444444444"),
                "1".repeat(64), "request-2", "jti-2", T0.plusSeconds(30), configuration()
        );

        var returned = store.admit(replay);

        assertThat(created.created()).isTrue();
        assertThat(returned.created()).isFalse();
        assertThat(returned.runId()).isEqualTo(created.runId());
        assertThat(returned.confirmationId()).isEqualTo(created.confirmationId());
        assertThat(jdbcClient.sql("select count(*) from inference_run").query(Long.class).single())
                .isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from external_transfer_confirmation")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("select request_id from external_transfer_confirmation")
                .query(String.class).single()).isEqualTo("request-1");
    }

    @Test
    void manifestOrTermsDriftReturnsTypedConflictWithoutASecondRun() {
        store.admit(command(
                IDEMPOTENCY_KEY,
                uuid("11111111-1111-4111-8111-111111111111"),
                uuid("22222222-2222-4222-8222-222222222222"),
                "1".repeat(64), "request-1", "jti-1", T0, configuration()
        ));

        var problem = assertThrows(LiveAdmissionProblem.class, () -> store.admit(command(
                IDEMPOTENCY_KEY,
                uuid("33333333-3333-4333-8333-333333333333"),
                uuid("44444444-4444-4444-8444-444444444444"),
                "2".repeat(64), "request-2", "jti-2", T0.plusSeconds(30), configuration()
        )));

        assertEquals("LIVE_IDEMPOTENCY_CONFLICT", problem.code());
        assertThat(jdbcClient.sql("select count(*) from inference_run").query(Long.class).single())
                .isEqualTo(1L);
        assertThat(jdbcClient.sql("select count(*) from inference_artifact")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void runManifestAndConfirmationRollbackTogetherAtTheLastWrite() {
        var duplicateConfirmationId = uuid("22222222-2222-4222-8222-222222222222");
        store.admit(command(
                IDEMPOTENCY_KEY,
                uuid("11111111-1111-4111-8111-111111111111"),
                duplicateConfirmationId,
                "1".repeat(64), "request-1", "jti-1", T0, configuration()
        ));
        var secondRunId = uuid("33333333-3333-4333-8333-333333333333");

        assertThatThrownBy(() -> store.admit(command(
                "live-idempotency-002", secondRunId, duplicateConfirmationId,
                "2".repeat(64), "request-2", "jti-2", T0.plusSeconds(1), configuration()
        ))).isInstanceOf(RuntimeException.class);

        assertThat(jdbcClient.sql("select count(*) from inference_run where run_id = :runId")
                .param("runId", secondRunId).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from live_input_manifest where run_id = :runId")
                .param("runId", secondRunId).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from inference_artifact where artifact_id = :id")
                .param("id", "2".repeat(64)).query(Long.class).single()).isZero();
    }

    @Test
    void noticeManifestAndConfirmationAreAppendOnlyAndCarryNoSourceNameColumn() {
        var admitted = store.admit(command(
                IDEMPOTENCY_KEY,
                uuid("11111111-1111-4111-8111-111111111111"),
                uuid("22222222-2222-4222-8222-222222222222"),
                "1".repeat(64), "request-1", "jti-1", T0, configuration()
        ));
        var persisted = store.findConfirmation(admitted.runId()).orElseThrow();

        assertThat(persisted.dispatchNotAfter()).isEqualTo(T0.plusSeconds(900));
        assertThat(persisted.providerCallsNotAfter()).isEqualTo(T0.plusSeconds(7200));
        assertThat(jdbcClient.sql("""
                        select count(*) from information_schema.columns
                        where table_schema = 'public'
                          and table_name in ('external_transfer_notice', 'live_input_manifest',
                                             'live_input_manifest_item', 'external_transfer_confirmation')
                          and column_name in ('filename', 'file_name', 'source_name', 'original_name')
                        """).query(Long.class).single()).isZero();
        assertThatThrownBy(() -> jdbcClient.sql("""
                        update external_transfer_confirmation
                        set maximum_provider_calls = maximum_provider_calls
                        where run_id = :runId
                        """).param("runId", admitted.runId()).update())
                .hasStackTraceContaining("live admission facts are append-only");
    }

    private static NewLiveInferenceRun command(
            String idempotencyKey,
            UUID runId,
            UUID confirmationId,
            String artifactId,
            String requestId,
            String jti,
            Instant confirmedAt,
            LiveAdmissionConfiguration configuration
    ) {
        var artifact = new NormalizedArtifact(
                artifactId, NormalizedArtifact.Kind.IMAGE, "memory:" + artifactId,
                "image/png", 64, 4, 4
        );
        var initial = new NormalizedInput(
                InferenceMode.IMAGE_ONLY, configuration.profile().profile().profileId(),
                "production-live", "0".repeat(64), List.of(artifact),
                List.of(new NormalizedInputReference(NormalizedArtifact.Kind.IMAGE, 0, artifactId)),
                List.of()
        );
        var manifest = LiveInputManifest.from(initial);
        var normalized = new NormalizedInput(
                initial.mode(), initial.profileId(), initial.sourceReference(), manifest.sha256(),
                initial.artifacts(), initial.references(), initial.newlyCreatedLocators()
        );
        var identity = new GatewayRequestIdentity(
                "actor-opaque-001", requestId, jti, "POST", "/api/v1/inference-runs/live",
                GatewayAssertionAuthority.idempotencyKeyDigest(idempotencyKey),
                confirmedAt, confirmedAt.plusSeconds(60), "gateway-2026-08-a"
        );
        var confirmation = ExternalTransferConfirmation.issue(
                confirmationId, runId, identity,
                InputProvenance.USER_PROVIDED, SensitivityClass.ORDINARY_DESIGN,
                configuration, manifest, confirmedAt
        );
        var run = new NewInferenceRun(
                runId, idempotencyKey, confirmation.requestFingerprint(), normalized,
                configuration.profile().snapshotJson(),
                configuration.notice().maximumCostMicrosCny(), Optional.empty(), confirmedAt
        );
        return new NewLiveInferenceRun(run, manifest, configuration.notice(), confirmation);
    }

    private static LiveAdmissionConfiguration configuration() {
        var profile = new InferenceProfileRegistry().require(
                "dashscope-qwen38-max-product-v52-hybrid-generic"
        );
        var value = profile.profile();
        var notice = ExternalTransferNotice.issue(
                "renderweave-external-transfer-notice/1.0", "zh-CN",
                "Provider legal entity per the accepted standard online terms",
                value.provider(), value.model(), value.providerEndpoint(), "cn-beijing",
                "Generate a review-only RenderWeave schema Candidate from user-provided design images.",
                "No numerical Provider retention guarantee is claimed.",
                "No Provider secondary-use guarantee is claimed.",
                "Provider terms may permit technical or human review.",
                value.profileId(), profile.canonicalSha256(), value.maximumTotalCalls(),
                value.maximumEstimatedCostMicrosCny(), 7L * 24 * 60 * 60,
                "renderweave-image-only-admission-policy/1.0", "a".repeat(64),
                "dashscope-standard-pay-as-you-go-terms/2026-08-17", "b".repeat(64)
        );
        return new LiveAdmissionConfiguration(notice, profile);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
