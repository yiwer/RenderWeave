package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.ImageOnlyAdmissionPolicyStore;
import cn.hbads.renderweave.inference.admission.LiveAdmissionProblem;
import cn.hbads.renderweave.inference.admission.ProviderCallAuthorizationCommand;
import cn.hbads.renderweave.inference.admission.ProviderEgressPermit;
import cn.hbads.renderweave.inference.audit.LiveAdmissionAuditChain;
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
import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;
import cn.hbads.renderweave.inference.provider.ProviderBudgetStore;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderInferenceResponse;
import cn.hbads.renderweave.inference.provider.ProviderUsage;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.retention.PayloadAccessGuard;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.run.NewInferenceRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
        "renderweave.inference.blob-root=target/test-audit-dual-switch-blobs",
        "DASHSCOPE_API_KEY=",
        "DASHSCOPE_API_KEY_FILE="
})
class PostgresLiveAuditChainAndDualSwitchTest {
    private static final Instant T0 = Instant.parse("2026-08-18T09:00:00Z");
    private static final String PROFILE_ID = "dashscope-qwen37-flash-v1";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    static Path egressPermitDirectory;

    private static Path egressPermitFile;

    @DynamicPropertySource
    static void egressPermitProperties(DynamicPropertyRegistry registry) {
        egressPermitFile = egressPermitDirectory.resolve("provider-egress-permit.txt");
        registry.add("renderweave.inference.egress-permit-file", () -> egressPermitFile.toString());
    }

    @Autowired
    private InferenceRunStore runs;

    @Autowired
    private InferenceReplayStore workflowStore;

    @Autowired
    private ProviderBudgetStore budgets;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ImageOnlyAdmissionPolicyStore policyStore;

    @Autowired
    private PostgresLiveAuditStore auditStore;

    @Autowired
    private PostgresLiveProviderCallGate gate;

    @Autowired
    private PostgresAuditIntegrityProbe auditProbe;

    private final InferenceProfileRegistry profiles = new InferenceProfileRegistry();
    private final CandidateJsonCodec candidateCodec = new CandidateJsonCodec();

    @BeforeEach
    void resetState() throws IOException {
        setAppendOnlyTriggers(false);
        jdbcClient.sql("delete from provider_call_authorization").update();
        jdbcClient.sql("delete from live_admission_audit_event").update();
        jdbcClient.sql("delete from inference_provider_reservation").update();
        jdbcClient.sql("delete from inference_run").update();
        jdbcClient.sql("delete from inference_artifact").update();
        setAppendOnlyTriggers(true);
        openEgress();
        if (!policyStore.current().enabled()) {
            policyStore.append(true, "test-ops-mtls-identity", "OPS_ENABLED", T0);
        }
    }

    private void setAppendOnlyTriggers(boolean enabled) {
        var action = enabled ? "enable" : "disable";
        jdbcClient.sql("alter table provider_call_authorization " + action
                + " trigger provider_call_authorization_append_only").update();
        jdbcClient.sql("alter table live_admission_audit_event " + action
                + " trigger live_admission_audit_event_append_only").update();
    }

    @Test
    void providerDispatchCommitsAuthorizationReservationAndAuditAtomically() throws Exception {
        var blobs = new MemoryBlobStore();
        var runId = createRun(blobs, "audit-atomic");
        var provider = new CountingProvider(request -> response(request, candidate(request)));

        var finished = worker(provider, blobs).processNext("audit-atomic-worker").orElseThrow();

        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.calls).isEqualTo(1);
        var events = auditStore.eventsForRun(runId);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).eventCode()).isEqualTo("CALL_AUTHORIZED");
        assertThat(events.get(1).eventCode()).isEqualTo("CALL_DISPATCH_SUCCEEDED");
        assertThat(events.get(1).usageInputTokens()).isEqualTo(1_000L);
        assertThat(events.get(1).costMicrosCny()).isPositive();
        assertThat(LiveAdmissionAuditChain.verify(events)).isEqualTo(LiveAdmissionAuditChain.Verdict.OK);
        assertThat(jdbcClient.sql("""
                        select policy_version, egress_permit_identity, endpoint
                        from provider_call_authorization where run_id = :runId
                        """).param("runId", runId)
                .query((resultSet, row) -> Map.of(
                        "policyVersion", resultSet.getInt("policy_version"),
                        "egress", resultSet.getString("egress_permit_identity"),
                        "endpoint", resultSet.getString("endpoint")))
                .single())
                .containsEntry("egress", "test-egress-permit-1")
                .containsEntry(
                        "endpoint", profiles.require(PROFILE_ID).profile().providerEndpoint());
    }

    @Test
    void onlyTheElevenSwitchCombinationReachesTheProvider() throws Exception {
        closePolicy();
        closeEgress();
        assertDrainedWithoutProviderCall("policy-egress-00", "LIVE_POLICY_DISABLED");

        openEgress();
        assertDrainedWithoutProviderCall("policy-egress-01", "LIVE_POLICY_DISABLED");

        openPolicy();
        closeEgress();
        assertDrainedWithoutProviderCall("policy-egress-10", "EGRESS_DISABLED");

        openEgress();
        var blobs = new MemoryBlobStore();
        var runId = createRun(blobs, "policy-egress-11");
        var provider = new CountingProvider(request -> response(request, candidate(request)));
        var finished = worker(provider, blobs).processNext("policy-egress-11-worker").orElseThrow();
        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void queuedRunsDrainToStableTerminalsAndNeverResurrect() throws Exception {
        var blobs = new MemoryBlobStore();
        var runId = createRun(blobs, "drain-no-resurrection");
        closePolicy();

        var drained = new CountingProvider(request -> {
            throw new AssertionError("A closed switch must never reach the Provider");
        });
        var terminal = worker(drained, blobs).processNext("drain-worker").orElseThrow();
        assertThat(terminal.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(terminal.failureCode()).contains("LIVE_POLICY_DISABLED");
        assertThat(drained.calls).isZero();
        assertThat(auditStore.eventsForRun(runId).stream().map(event -> event.eventCode()).toList())
                .containsExactly("RUN_DRAINED_POLICY");

        openPolicy();
        assertThat(worker(drained, blobs).processNext("resurrection-worker")).isEmpty();
        assertThat(runs.find(runId).orElseThrow().state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(drained.calls).isZero();
    }

    @Test
    void committedAuthorizationWithoutDispatchIsNeverReplayedBlindly() throws Exception {
        var blobs = new MemoryBlobStore();
        var runId = createRun(blobs, "crash-after-commit");
        var profile = profiles.require(PROFILE_ID).profile();
        gate.authorizeCall(callCommand(runId, 0, profile.maximumEstimatedCostMicrosCny()));

        var provider = new CountingProvider(request -> response(request, candidate(request)));
        var terminal = worker(provider, blobs).processNext("crash-replay-worker").orElseThrow();

        assertThat(provider.calls).isZero();
        assertThat(terminal.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(jdbcClient.sql(
                        "select count(*) from provider_call_authorization where run_id = :runId")
                .param("runId", runId).query(Long.class).single()).isEqualTo(1L);
        assertThat(auditStore.eventsForRun(runId)).hasSize(1);
    }

    @Test
    void authorizationFailureRollsBackReservationAndAuditTogether() throws Exception {
        var blobs = new MemoryBlobStore();
        var runId = createRun(blobs, "atomic-rollback");
        var profile = profiles.require(PROFILE_ID).profile();
        gate.authorizeCall(callCommand(runId, 0, profile.maximumEstimatedCostMicrosCny()));

        assertThatThrownBy(() -> gate.authorizeCall(
                callCommand(runId, 0, profile.maximumEstimatedCostMicrosCny())))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbcClient.sql(
                        "select count(*) from inference_provider_reservation where run_id = :runId")
                .param("runId", runId).query(Long.class).single()).isEqualTo(1L);
        assertThat(auditStore.eventsForRun(runId)).hasSize(1);
        assertThat(jdbcClient.sql(
                        "select count(*) from provider_call_authorization where run_id = :runId")
                .param("runId", runId).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void tamperedChainFailsIndependentReplayAndBlocksNewCalls() throws Exception {
        var blobs = new MemoryBlobStore();
        var runId = createRun(blobs, "tamper-replay");
        var provider = new CountingProvider(request -> response(request, candidate(request)));
        worker(provider, blobs).processNext("tamper-seed-worker").orElseThrow();

        jdbcClient.sql("""
                        alter table live_admission_audit_event
                        disable trigger live_admission_audit_event_append_only
                        """).update();
        jdbcClient.sql("""
                        update live_admission_audit_event
                        set cost_micros_cny = cost_micros_cny + 1
                        where run_id = :runId and sequence = 2
                        """).param("runId", runId).update();
        jdbcClient.sql("""
                        alter table live_admission_audit_event
                        enable trigger live_admission_audit_event_append_only
                        """).update();

        assertThat(LiveAdmissionAuditChain.verify(auditStore.eventsForRun(runId)))
                .isEqualTo(LiveAdmissionAuditChain.Verdict.TAMPERED);
        var probe = auditProbe.snapshot();
        assertThat(probe.healthy()).isFalse();
        assertThat(probe.reasonCode()).isEqualTo("AUDIT_INTEGRITY_UNAVAILABLE");

        jdbcClient.sql("update inference_run set state = 'QUEUED', stage = 'OBSERVE',"
                + " lease_expires_at = null, lease_owner = null, lease_token = null,"
                + " finished_at = null, failure_code = null where run_id = :runId")
                .param("runId", runId).update();
        var replayed = worker(provider, blobs).processNext("tamper-replay-worker");
        assertThat(replayed.orElseThrow().state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(provider.calls).isEqualTo(1);
        repairChain(runId);
    }

    @Test
    void deletedAuditEventIsDetectedAsMissingBeforeAnyNewCall() throws Exception {
        var blobs = new MemoryBlobStore();
        var runId = createRun(blobs, "delete-replay");
        var provider = new CountingProvider(request -> response(request, candidate(request)));
        worker(provider, blobs).processNext("delete-seed-worker").orElseThrow();

        jdbcClient.sql("""
                        alter table live_admission_audit_event
                        disable trigger live_admission_audit_event_append_only
                        """).update();
        jdbcClient.sql("delete from live_admission_audit_event where run_id = :runId and sequence = 1")
                .param("runId", runId).update();
        jdbcClient.sql("""
                        alter table live_admission_audit_event
                        enable trigger live_admission_audit_event_append_only
                        """).update();

        assertThat(LiveAdmissionAuditChain.verify(auditStore.eventsForRun(runId)))
                .isEqualTo(LiveAdmissionAuditChain.Verdict.MISSING);
        assertThat(auditProbe.snapshot().healthy()).isFalse();
        repairChain(runId);
    }

    @Test
    void runtimeRoleCanInsertAndSelectButNeverUpdateOrDeleteAuditFacts() throws Exception {
        var blobs = new MemoryBlobStore();
        var runId = createRun(blobs, "runtime-role");
        var provider = new CountingProvider(request -> response(request, candidate(request)));
        worker(provider, blobs).processNext("runtime-role-worker").orElseThrow();
        var chain = auditStore.eventsForRun(runId);

        jdbcClient.sql("drop role if exists audit_runtime_probe_login").update();
        jdbcClient.sql("create role audit_runtime_probe_login login password 'audit-probe'").update();
        jdbcClient.sql("grant renderweave_live_runtime to audit_runtime_probe_login").update();
        var url = String.format(
                "jdbc:postgresql://%s:%s/%s",
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()
        );
        try (var connection = DriverManager.getConnection(url, "audit_runtime_probe_login", "audit-probe")) {
            try (var statement = connection.createStatement()) {
                try (var resultSet = statement.executeQuery(
                        "select count(*) from live_admission_audit_event")) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getLong(1)).isEqualTo(chain.size());
                }
                var next = chain.size() + 1;
                var previous = chain.get(chain.size() - 1).eventDigest();
                var insert = connection.prepareStatement("""
                        insert into live_admission_audit_event (
                            run_id, sequence, event_code, occurred_at,
                            previous_event_digest, event_digest
                        ) values (?::uuid, ?, 'CALL_ATTEMPT_AMBIGUOUS', now(), ?, ?)
                        """);
                insert.setString(1, runId.toString());
                insert.setInt(2, next);
                insert.setString(3, previous);
                insert.setString(4, "c".repeat(64));
                assertThat(insert.executeUpdate()).isEqualTo(1);
                assertThatThrownBy(() -> statement.executeUpdate("""
                                update live_admission_audit_event
                                set decision_code = 'FORGED' where run_id = '""" + runId + "'"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
                assertThatThrownBy(() -> statement.executeUpdate(
                                "delete from live_admission_audit_event where run_id = '" + runId + "'"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
        } finally {
            jdbcClient.sql("revoke renderweave_live_runtime from audit_runtime_probe_login").update();
            jdbcClient.sql("drop role audit_runtime_probe_login").update();
            jdbcClient.sql("""
                            alter table live_admission_audit_event
                            disable trigger live_admission_audit_event_append_only
                            """).update();
            jdbcClient.sql("""
                            delete from live_admission_audit_event
                            where run_id = :runId and event_code = 'CALL_ATTEMPT_AMBIGUOUS'
                            """).param("runId", runId).update();
            jdbcClient.sql("""
                            alter table live_admission_audit_event
                            enable trigger live_admission_audit_event_append_only
                            """).update();
        }
    }

    @Test
    void reviewRequiredRunsStayReviewableWhileSwitchesAreClosed() throws Exception {
        var blobs = new MemoryBlobStore();
        var runId = createRun(blobs, "review-survives-drain");
        var provider = new CountingProvider(request -> response(request, candidate(request)));
        var finished = worker(provider, blobs).processNext("review-seed-worker").orElseThrow();
        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);

        closePolicy();
        closeEgress();

        assertThat(runs.find(runId).orElseThrow().state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(workflowStore.findCandidate(runId)).isPresent();
        assertThat(worker(provider, blobs).processNext("review-drain-worker")).isEmpty();
    }

    @Test
    void defaultDeploymentBootsClosedForPolicyAndEgress() {
        assertThat(jdbcClient.sql("""
                        select enabled from image_only_admission_policy
                        where policy_version = 1
                        """).query(Boolean.class).single()).isFalse();
        assertThat(FileSystemProviderEgressPermit.fromConfiguration("").snapshot())
                .isEqualTo(ProviderEgressPermit.Snapshot.DISABLED);
        assertThat(FileSystemProviderEgressPermit
                .fromConfiguration(egressPermitDirectory.resolve("absent.txt").toString())
                .snapshot().enabled()).isFalse();
    }

    private void assertDrainedWithoutProviderCall(String seed, String expectedCode) {
        var blobs = new MemoryBlobStore();
        var runId = createRun(blobs, seed);
        var provider = new CountingProvider(request -> {
            throw new AssertionError("A closed switch must never reach the Provider");
        });
        var terminal = worker(provider, blobs).processNext(seed + "-worker").orElseThrow();
        assertThat(terminal.state()).isEqualTo(InferenceRunState.FAILED);
        assertThat(terminal.failureCode()).contains(expectedCode);
        assertThat(provider.calls).isZero();
        assertThat(jdbcClient.sql(
                        "select count(*) from inference_provider_reservation where run_id = :runId")
                .param("runId", runId).query(Long.class).single()).isZero();
        assertThat(auditStore.eventsForRun(runId).stream()
                .noneMatch(event -> "CALL_AUTHORIZED".equals(event.eventCode()))).isTrue();
        repairChain(runId);
    }

    private ProviderCallAuthorizationCommand callCommand(UUID runId, int ordinal, long costLimit) {
        var profile = profiles.require(PROFILE_ID).profile();
        return new ProviderCallAuthorizationCommand(
                runId, InferenceMode.IMAGE_ONLY, ordinal, "product-live",
                profile.profileId(),
                profiles.require(PROFILE_ID).canonicalSha256(),
                profile.providerEndpoint(), "f".repeat(64),
                10_000L, ProfileRunBudgetPolicy.effectiveRunCostLimit(profile, costLimit),
                T0.plusSeconds(1)
        );
    }

    private void repairChain(UUID runId) {
        jdbcClient.sql("""
                        alter table live_admission_audit_event
                        disable trigger live_admission_audit_event_append_only
                        """).update();
        jdbcClient.sql("delete from live_admission_audit_event where run_id = :runId")
                .param("runId", runId).update();
        jdbcClient.sql("""
                        alter table live_admission_audit_event
                        enable trigger live_admission_audit_event_append_only
                        """).update();
    }

    private void openPolicy() {
        if (!policyStore.current().enabled()) {
            policyStore.append(true, "test-ops-mtls-identity", "OPS_ENABLED", T0);
        }
    }

    private void closePolicy() {
        if (policyStore.current().enabled()) {
            policyStore.append(false, "test-ops-mtls-identity", "OPS_DISABLED", T0);
        }
    }

    private static void openEgress() throws IOException {
        Files.writeString(egressPermitFile, """
                renderweave-provider-egress-permit/1.0
                enabled=true
                identity=test-egress-permit-1
                """, StandardCharsets.UTF_8);
    }

    private static void closeEgress() throws IOException {
        Files.deleteIfExists(egressPermitFile);
    }

    private LiveInferenceWorker worker(CountingProvider provider, MemoryBlobStore blobs) {
        return new LiveInferenceWorker(
                runs, workflowStore, budgets, provider, blobs,
                Clock.fixed(T0.plusSeconds(1), ZoneOffset.UTC), Duration.ofMinutes(5),
                PayloadAccessGuard.allowAll(), gate
        );
    }

    private UUID createRun(MemoryBlobStore blobs, String seed) {
        var profile = profiles.require(PROFILE_ID);
        var bytes = ("synthetic-image:" + seed).getBytes(StandardCharsets.UTF_8);
        var artifactId = sha256(bytes);
        blobs.values.put(artifactId, bytes);
        var artifact = new NormalizedArtifact(
                artifactId, NormalizedArtifact.Kind.IMAGE, artifactId,
                "image/png", bytes.length, 32, 16
        );
        var normalized = new NormalizedInput(
                InferenceMode.IMAGE_ONLY, PROFILE_ID, seed, sha256(seed.getBytes(StandardCharsets.UTF_8)),
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class CountingProvider implements InferenceProvider {
        private final Function<ProviderInferenceRequest, ProviderInferenceResponse> behavior;
        private int calls;

        private CountingProvider(Function<ProviderInferenceRequest, ProviderInferenceResponse> behavior) {
            this.behavior = behavior;
        }

        @Override
        public ProviderInferenceResponse complete(ProviderInferenceRequest request) {
            calls++;
            return behavior.apply(request);
        }

        @Override
        public boolean configured() {
            return true;
        }
    }

    private static final class MemoryBlobStore implements BlobStore {
        private final Map<String, byte[]> values = new LinkedHashMap<>();

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
}
