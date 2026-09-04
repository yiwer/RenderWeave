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
import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.InferenceStorageException;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.input.NormalizedInput;
import cn.hbads.renderweave.inference.input.NormalizedInputReference;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.retention.PayloadAccess;
import cn.hbads.renderweave.inference.retention.PayloadDeletionReason;
import cn.hbads.renderweave.inference.retention.PayloadLifecycleException;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.run.NewInferenceRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest
class PostgresPayloadLifecycleStoreTest {
    private static final Instant T0 = Instant.parse("2026-08-18T08:00:00Z");
    private static final String ARTIFACT = "7".repeat(64);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PostgresLiveAdmissionStore admissions;

    @Autowired
    private InferenceRunStore runs;

    private FaultInjectingBlobStore blobs;

    @BeforeEach
    void clear() {
        jdbcClient.sql("""
                        truncate table payload_artifact_deletion_task,
                                       inference_artifact_ingest_lease,
                                       inference_artifact_envelope,
                                       external_transfer_notice,
                                       inference_run,
                                       inference_artifact cascade
                        """).update();
        blobs = new FaultInjectingBlobStore();
    }

    @Test
    void sharedRetainedReferenceKeepsOriginalExpiryAndDeletesOnlyAfterLastTombstone() {
        var first = admit(1, ARTIFACT, T0);
        var second = admit(2, ARTIFACT, T0.plusSeconds(60));
        blobs.add(ARTIFACT);

        var grants = jdbcClient.sql("""
                        select run_id, origin_run_id, first_uploaded_at, payload_expires_at
                        from inference_payload_retention order by created_at, run_id
                        """)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getObject("run_id", UUID.class),
                        resultSet.getObject("origin_run_id", UUID.class),
                        resultSet.getObject("first_uploaded_at", java.time.OffsetDateTime.class).toInstant(),
                        resultSet.getObject("payload_expires_at", java.time.OffsetDateTime.class).toInstant()
                ))
                .list();
        assertThat(grants).hasSize(2);
        assertThat(grants.get(1).get(1)).isEqualTo(first);
        assertThat(grants.get(1).get(2)).isEqualTo(T0);
        assertThat(grants.get(1).get(3)).isEqualTo(T0.plusSeconds(7L * 24 * 60 * 60));
        assertThatThrownBy(() -> jdbcClient.sql("""
                        update inference_payload_retention
                        set payload_expires_at = payload_expires_at + interval '1 second'
                        where run_id = :runId
                        """).param("runId", second).update())
                .hasStackTraceContaining("payload lifecycle facts are append-only");

        var firstLifecycle = lifecycle(T0.plusSeconds(120));
        firstLifecycle.tombstone(first, PayloadDeletionReason.USER_REQUESTED);
        var deferred = firstLifecycle.drainDeletionTasks(1);
        assertThat(deferred.deferred()).isEqualTo(1);
        assertThat(blobs.deleteAttempts).isZero();
        assertThat(taskState(ARTIFACT)).isEqualTo("SUPERSEDED");

        var finalLifecycle = lifecycle(T0.plusSeconds(180));
        finalLifecycle.tombstone(second, PayloadDeletionReason.USER_REQUESTED);
        var deleted = finalLifecycle.drainDeletionTasks(1);
        assertThat(deleted.deleted()).isEqualTo(1);
        assertThat(blobs.contains(ARTIFACT)).isFalse();
        assertThat(taskState(ARTIFACT)).isEqualTo("DELETED");
    }

    @Test
    void tombstoneSurvivesPhysicalDeleteFailureBlocksAllAccessAndTripsHardSlo() {
        var runId = admit(3, ARTIFACT, T0);
        blobs.add(ARTIFACT);
        blobs.failNextDeletes = 1;
        var lifecycle = lifecycle(T0);

        var tombstone = lifecycle.tombstone(runId, PayloadDeletionReason.USER_REQUESTED);
        var firstDrain = lifecycle.drainDeletionTasks(1);

        assertThat(tombstone.created()).isTrue();
        assertThat(firstDrain.failed()).isEqualTo(1);
        assertThat(taskState(ARTIFACT)).isEqualTo("PENDING");
        assertThat(blobs.contains(ARTIFACT)).isTrue();
        for (var access : PayloadAccess.values()) {
            assertCode("LIVE_PAYLOAD_DELETED", () -> lifecycle.require(runId, access));
        }
        assertThatThrownBy(() -> jdbcClient.sql("""
                        update payload_deletion_tombstone
                        set reason = reason where run_id = :runId
                        """).param("runId", runId).update())
                .hasStackTraceContaining("payload lifecycle facts are append-only");

        var overdue = lifecycle(T0.plusSeconds(24L * 60 * 60));
        assertThat(overdue.snapshot().healthy()).isFalse();
        assertThat(overdue.snapshot().reasonCode()).isEqualTo("PAYLOAD_DELETION_UNHEALTHY");
        var blockedAdmission = new PostgresLiveAdmissionStore(
                jdbcClient, runs, overdue, new PostgresLiveAuditStore(jdbcClient)
        );
        var problem = assertThrows(LiveAdmissionProblem.class, () -> blockedAdmission.admit(
                command(4, "8".repeat(64), T0.plusSeconds(24L * 60 * 60))
        ));
        assertEquals("PAYLOAD_DELETION_UNHEALTHY", problem.code());
        assertThat(overdue.drainDeletionTasks(1).deleted()).isEqualTo(1);
        assertThat(overdue.snapshot().healthy()).isTrue();
    }

    @Test
    void retryBoundaryRequiresFreshConfirmationThenReuploadAndFinallyExpires() {
        var runId = admit(5, ARTIFACT, T0);

        assertCode(
                "LIVE_RETRY_REQUIRES_FRESH_CONFIRMATION",
                () -> lifecycle(T0.plusSeconds(6L * 24 * 60 * 60))
                        .require(runId, PayloadAccess.RETRY)
        );
        assertCode(
                "LIVE_PAYLOAD_REUPLOAD_REQUIRED",
                () -> lifecycle(T0.plusSeconds(6L * 24 * 60 * 60 + 1))
                        .require(runId, PayloadAccess.RETRY)
        );
        assertCode(
                "LIVE_PAYLOAD_EXPIRED",
                () -> lifecycle(T0.plusSeconds(7L * 24 * 60 * 60))
                        .require(runId, PayloadAccess.READ)
        );
    }

    @Test
    void reviewExpiryCreatesTombstoneAndStableLiveReviewExpiredTerminal() {
        var runId = admit(6, ARTIFACT, T0);
        jdbcClient.sql("""
                        update inference_run
                        set state = 'REVIEW_REQUIRED', stage = 'USER_APPROVAL',
                            sequence = sequence + 1, updated_at = :now
                        where run_id = :runId
                        """)
                .param("now", java.time.OffsetDateTime.ofInstant(T0.plusSeconds(1), ZoneOffset.UTC))
                .param("runId", runId)
                .update();

        var lifecycle = lifecycle(T0.plusSeconds(7L * 24 * 60 * 60));
        assertThat(lifecycle.sweepDueRuns(10)).isEqualTo(1);

        var terminal = jdbcClient.sql("""
                        select state, failure_code from inference_run where run_id = :runId
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getString("state"), resultSet.getString("failure_code")
                ))
                .single();
        assertThat(terminal).containsExactly("FAILED", "LIVE_REVIEW_EXPIRED");
        assertThat(tombstoneReason(runId)).isEqualTo("PAYLOAD_EXPIRED");
        assertCode("LIVE_PAYLOAD_DELETED", () -> lifecycle.require(runId, PayloadAccess.APPLY));
    }

    @Test
    void activeIngestLeaseClosesNormalizationToAdmissionDeletionRace() {
        var runId = admit(7, ARTIFACT, T0);
        blobs.add(ARTIFACT);
        jdbcClient.sql("""
                        insert into inference_artifact_ingest_lease (artifact_id, observed_at, expires_at)
                        values (:artifactId, :observedAt, :expiresAt)
                        """)
                .param("artifactId", ARTIFACT)
                .param("observedAt", java.time.OffsetDateTime.ofInstant(T0, ZoneOffset.UTC))
                .param("expiresAt", java.time.OffsetDateTime.ofInstant(T0.plusSeconds(900), ZoneOffset.UTC))
                .update();
        var initial = lifecycle(T0);
        initial.tombstone(runId, PayloadDeletionReason.USER_REQUESTED);

        var deferred = initial.drainDeletionTasks(1);
        assertThat(deferred.deferred()).isEqualTo(1);
        assertThat(blobs.deleteAttempts).isZero();
        assertThat(taskState(ARTIFACT)).isEqualTo("PENDING");

        var afterLease = lifecycle(T0.plusSeconds(900));
        assertThat(afterLease.sweepExpiredIngestLeases(10)).isEqualTo(1);
        assertThat(afterLease.drainDeletionTasks(1).deleted()).isEqualTo(1);
        assertThat(blobs.contains(ARTIFACT)).isFalse();
    }

    @Test
    void completedRunIsTombstonedWithoutChangingItsTerminalState() {
        var runId = admit(8, ARTIFACT, T0);
        jdbcClient.sql("""
                        update inference_run
                        set state = 'COMPLETED', stage = 'ATOMIC_CREATE',
                            sequence = sequence + 1, finished_at = :now, updated_at = :now
                        where run_id = :runId
                        """)
                .param("now", java.time.OffsetDateTime.ofInstant(T0.plusSeconds(30), ZoneOffset.UTC))
                .param("runId", runId)
                .update();

        var result = lifecycle(T0.plusSeconds(30)).tombstoneCompleted(runId, T0.plusSeconds(30));

        assertThat(result.reason()).isEqualTo(PayloadDeletionReason.COMPLETED);
        assertThat(tombstoneReason(runId)).isEqualTo("COMPLETED");
        assertThat(jdbcClient.sql("select state from inference_run where run_id = :runId")
                .param("runId", runId).query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(taskState(ARTIFACT)).isEqualTo("PENDING");
    }

    private UUID admit(int ordinal, String artifactId, Instant confirmedAt) {
        var command = command(ordinal, artifactId, confirmedAt);
        admissions.admit(command);
        return command.run().runId();
    }

    private PostgresPayloadLifecycleStore lifecycle(Instant now) {
        return new PostgresPayloadLifecycleStore(
                jdbcClient, blobs, Clock.fixed(now, ZoneOffset.UTC), transactionManager
        );
    }

    private String taskState(String artifactId) {
        return jdbcClient.sql("""
                        select state from payload_artifact_deletion_task where artifact_id = :artifactId
                        """)
                .param("artifactId", artifactId)
                .query(String.class)
                .single();
    }

    private String tombstoneReason(UUID runId) {
        return jdbcClient.sql("""
                        select reason from payload_deletion_tombstone where run_id = :runId
                        """)
                .param("runId", runId)
                .query(String.class)
                .single();
    }

    private static void assertCode(String expected, Runnable operation) {
        var problem = assertThrows(PayloadLifecycleException.class, operation::run);
        assertEquals(expected, problem.code());
    }

    private static NewLiveInferenceRun command(int ordinal, String artifactId, Instant confirmedAt) {
        var idempotencyKey = "payload-lifecycle-" + ordinal;
        var runId = UUID.nameUUIDFromBytes(("run-" + ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var confirmationId = UUID.nameUUIDFromBytes(
                ("confirmation-" + ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        var configuration = configuration();
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
                "actor-opaque-001", "request-" + ordinal, "jti-" + ordinal,
                "POST", "/api/v1/inference-runs/live",
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

    private static final class FaultInjectingBlobStore implements BlobStore {
        private final Set<String> artifacts = new HashSet<>();
        private int failNextDeletes;
        private int deleteAttempts;

        void add(String artifactId) {
            artifacts.add(artifactId);
        }

        boolean contains(String artifactId) {
            return artifacts.contains(artifactId);
        }

        @Override
        public WriteReceipt write(String artifactId, byte[] bytes) {
            return new WriteReceipt(artifactId, artifacts.add(artifactId));
        }

        @Override
        public byte[] read(String locator) {
            if (!artifacts.contains(locator)) throw new IllegalStateException("missing synthetic artifact");
            return new byte[] {1};
        }

        @Override
        public void delete(String locator) {
            deleteAttempts++;
            if (failNextDeletes > 0) {
                failNextDeletes--;
                throw new InferenceStorageException(
                        "STORAGE_INJECTED_DELETE_FAILURE", "Synthetic delete failure", null
                );
            }
            artifacts.remove(locator);
        }
    }
}
