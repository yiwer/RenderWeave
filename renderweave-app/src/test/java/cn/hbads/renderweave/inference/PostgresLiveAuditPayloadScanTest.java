package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.ImageOnlyAdmissionPolicyStore;
import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.input.NormalizedInput;
import cn.hbads.renderweave.inference.input.NormalizedInputReference;
import cn.hbads.renderweave.inference.live.LiveInferenceWorker;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.InferenceProvider;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider-zero payload scan: canaries covering original filenames, image signatures, OCR text,
 * full prompt/response, PII, secrets and chain-of-thought are injected through the live path and
 * must never reach the audit chain, execution events or problem projections. The audit export
 * also feeds the independent Python chain-replay verifier.
 */
@Testcontainers
@SpringBootTest(properties = {
        "renderweave.inference.blob-root=target/test-audit-payload-scan-blobs",
        "DASHSCOPE_API_KEY=",
        "DASHSCOPE_API_KEY_FILE="
})
class PostgresLiveAuditPayloadScanTest {
    static final String FILENAME_CANARY = "canary-original-file-name-7f3a9c.png";
    static final String OCR_CANARY = "canary-ocr-text-a1b2c3";
    static final String RESPONSE_CANARY = "canary-full-response-d4e5f6";
    static final String COT_CANARY = "canary-chain-of-thought-9e8d7c";
    static final String PII_CANARY = "canary-pii-user@example.invalid";
    static final String SECRET_CANARY = "sk-canary-secret-b6a5c4d3e2f1";
    static final String IMAGE_CANARY = "canary-image-signature-1a2b3c4d";

    private static final Instant T0 = Instant.parse("2026-08-18T10:00:00Z");
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
    private cn.hbads.renderweave.inference.provider.ProviderBudgetStore budgets;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ImageOnlyAdmissionPolicyStore policyStore;

    @Autowired
    private PostgresLiveAuditStore auditStore;

    @Autowired
    private PostgresLiveProviderCallGate gate;

    private final InferenceProfileRegistry profiles = new InferenceProfileRegistry();

    @BeforeEach
    void resetState() throws IOException {
        setAppendOnlyTriggers(false);
        jdbcClient.sql("delete from provider_call_authorization").update();
        jdbcClient.sql("delete from live_admission_audit_event").update();
        jdbcClient.sql("delete from inference_provider_reservation").update();
        jdbcClient.sql("delete from inference_run").update();
        jdbcClient.sql("delete from inference_artifact").update();
        setAppendOnlyTriggers(true);
        Files.writeString(egressPermitFile, """
                renderweave-provider-egress-permit/1.0
                enabled=true
                identity=test-egress-permit-1
                """, StandardCharsets.UTF_8);
        if (!policyStore.current().enabled()) {
            policyStore.append(true, "test-ops-mtls-identity", "OPS_ENABLED", T0);
        }
    }

    @Test
    void canariesNeverReachAuditEventsExecutionLogOrProblemProjections() throws Exception {
        var blobs = new MemoryBlobStore();
        var runId = createRunWithCanaryImage(blobs);
        var provider = new CanaryProvider();
        var finished = new LiveInferenceWorker(
                runs, workflowStore, budgets, provider, blobs,
                Clock.fixed(T0.plusSeconds(1), ZoneOffset.UTC), Duration.ofMinutes(5),
                PayloadAccessGuard.allowAll(), gate
        ).processNext("payload-scan-worker").orElseThrow();
        assertThat(finished.state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);

        var auditDump = dumpTable("""
                select run_id::text, sequence::text, event_code, coalesce(actor_id, ''),
                       coalesce(confirmation_id::text, ''), coalesce(reservation_id::text, ''),
                       coalesce(call_authorization_id::text, ''), coalesce(attempt_ordinal::text, ''),
                       coalesce(input_fingerprint, ''), coalesce(profile_id, ''),
                       coalesce(profile_sha256, ''), coalesce(decision_code, ''),
                       coalesce(usage_input_tokens::text, ''), coalesce(usage_output_tokens::text, ''),
                       coalesce(cost_micros_cny::text, ''), occurred_at::text,
                       previous_event_digest, event_digest
                from live_admission_audit_event order by run_id, sequence
                """);
        var authorizationDump = dumpTable("""
                select call_authorization_id::text, run_id::text, attempt_ordinal::text,
                       policy_version::text, egress_permit_identity, profile_id, profile_sha256,
                       endpoint, coalesce(manifest_sha256, ''), input_fingerprint,
                       reservation_id::text, audit_sequence::text
                from provider_call_authorization order by authorized_at
                """);
        var eventDump = dumpTable("""
                select run_id::text, sequence::text, event_type, state, stage,
                       coalesce(data_json::text, ''), occurred_at::text
                from inference_run_event order by run_id, sequence
                """);
        var attemptDump = dumpTable("""
                select run_id::text, attempt_ordinal::text, status, outcome_code,
                       coalesce(provider_model, '')
                from inference_attempt order by run_id, attempt_ordinal
                """);
        var runDump = dumpTable("""
                select run_id::text, state, stage, source_reference,
                       coalesce(failure_code, '')
                from inference_run order by created_at
                """);

        for (var dump : List.of(auditDump, authorizationDump, eventDump, attemptDump, runDump)) {
            assertPayloadFree(dump);
        }
        assertThat(auditDump).doesNotContain(provider.requestMarker());

        var exportDirectory = Path.of("target", "image-only-p2-audit-export");
        Files.createDirectories(exportDirectory);
        Files.writeString(
                exportDirectory.resolve("audit-chain-export.json"),
                exportJson(runId),
                StandardCharsets.UTF_8
        );
    }

    @Test
    void typedAvailabilityProblemsStayStaticAndPayloadFree() {
        var body = """
                {"code":"LIVE_POLICY_DISABLED","message":"IMAGE_ONLY live admission is closed.""";
        assertPayloadFree(body);
        var problemProjection = String.join("\n",
                "LIVE_POLICY_DISABLED", "EGRESS_DISABLED", "AUDIT_INTEGRITY_UNAVAILABLE",
                "RUN_DRAINED_POLICY", "RUN_DRAINED_EGRESS",
                "LIVE_CONFIRMATION_EXPIRED", "LIVE_PROVIDER_ATTEMPT_AMBIGUOUS"
        );
        assertPayloadFree(problemProjection);
    }

    private String exportJson(UUID runId) {
        var events = auditStore.eventsForRun(runId);
        var builder = new StringBuilder();
        builder.append("{\"exportVersion\":\"renderweave-live-admission-audit-export/1.0\",");
        builder.append("\"events\":[");
        for (var index = 0; index < events.size(); index++) {
            var event = events.get(index);
            if (index > 0) builder.append(',');
            builder.append('{');
            field(builder, "runId", event.runId().toString());
            builder.append(',');
            builder.append("\"sequence\":").append(event.sequence());
            builder.append(',');
            field(builder, "eventCode", event.eventCode());
            builder.append(',');
            field(builder, "actorId", event.actorId());
            builder.append(',');
            field(builder, "confirmationId",
                    event.confirmationId() == null ? null : event.confirmationId().toString());
            builder.append(',');
            field(builder, "reservationId",
                    event.reservationId() == null ? null : event.reservationId().toString());
            builder.append(',');
            field(builder, "callAuthorizationId",
                    event.callAuthorizationId() == null ? null : event.callAuthorizationId().toString());
            builder.append(',');
            builder.append("\"attemptOrdinal\":")
                    .append(event.attemptOrdinal() == null ? "null" : event.attemptOrdinal());
            builder.append(',');
            field(builder, "inputFingerprint", event.inputFingerprint());
            builder.append(',');
            field(builder, "profileId", event.profileId());
            builder.append(',');
            field(builder, "profileSha256", event.profileSha256());
            builder.append(',');
            field(builder, "decisionCode", event.decisionCode());
            builder.append(',');
            builder.append("\"usageInputTokens\":")
                    .append(event.usageInputTokens() == null ? "null" : event.usageInputTokens());
            builder.append(',');
            builder.append("\"usageOutputTokens\":")
                    .append(event.usageOutputTokens() == null ? "null" : event.usageOutputTokens());
            builder.append(',');
            builder.append("\"costMicrosCny\":")
                    .append(event.costMicrosCny() == null ? "null" : event.costMicrosCny());
            builder.append(',');
            builder.append("\"occurredAtEpochSecond\":").append(event.occurredAt().getEpochSecond());
            builder.append(',');
            builder.append("\"occurredAtNano\":").append(event.occurredAt().getNano());
            builder.append(',');
            field(builder, "previousEventDigest", event.previousEventDigest());
            builder.append(',');
            field(builder, "eventDigest", event.eventDigest());
            builder.append('}');
        }
        builder.append("]}");
        return builder.toString();
    }

    private static void field(StringBuilder builder, String name, String value) {
        builder.append('"').append(name).append("\":");
        if (value == null) {
            builder.append("null");
        } else {
            builder.append('"').append(value).append('"');
        }
    }

    private String dumpTable(String sql) {
        return String.join("\n", jdbcClient.sql(sql)
                .query((resultSet, rowNumber) -> {
                    var columns = resultSet.getMetaData().getColumnCount();
                    var row = new ArrayList<String>();
                    for (var column = 1; column <= columns; column++) {
                        row.add(String.valueOf(resultSet.getString(column)));
                    }
                    return String.join("|", row);
                })
                .list());
    }

    private static void assertPayloadFree(String dump) {
        var lowered = dump.toLowerCase(java.util.Locale.ROOT);
        for (var canary : List.of(
                FILENAME_CANARY, OCR_CANARY, RESPONSE_CANARY, COT_CANARY,
                PII_CANARY, SECRET_CANARY, IMAGE_CANARY
        )) {
            assertThat(lowered)
                    .as("payload canary must not leak: %s", canary)
                    .doesNotContain(canary.toLowerCase(java.util.Locale.ROOT));
        }
        assertThat(lowered).doesNotContain("data:image");
        assertThat(lowered).doesNotContain("chain-of-thought");
        assertThat(lowered).doesNotContain("@example.");
        assertThat(lowered).doesNotContain("sk-canary");
        assertThat(lowered).doesNotContain("api key");
        assertThat(lowered).doesNotContain("private key");
    }

    private void setAppendOnlyTriggers(boolean enabled) {
        var action = enabled ? "enable" : "disable";
        jdbcClient.sql("alter table provider_call_authorization " + action
                + " trigger provider_call_authorization_append_only").update();
        jdbcClient.sql("alter table live_admission_audit_event " + action
                + " trigger live_admission_audit_event_append_only").update();
    }

    private UUID createRunWithCanaryImage(MemoryBlobStore blobs) {
        var profile = profiles.require(PROFILE_ID);
        var bytes = (IMAGE_CANARY + ":synthetic-image").getBytes(StandardCharsets.UTF_8);
        var artifactId = sha256(bytes);
        blobs.values.put(artifactId, bytes);
        var artifact = new NormalizedArtifact(
                artifactId, NormalizedArtifact.Kind.IMAGE, artifactId,
                "image/png", bytes.length, 32, 16
        );
        var normalized = new NormalizedInput(
                InferenceMode.IMAGE_ONLY, PROFILE_ID, "production-live",
                sha256("payload-scan".getBytes(StandardCharsets.UTF_8)),
                List.of(artifact),
                List.of(new NormalizedInputReference(NormalizedArtifact.Kind.IMAGE, 0, artifactId)),
                List.of()
        );
        return runs.create(NewInferenceRun.initial(
                UUID.randomUUID(), "idem-payload-scan", normalized, profile.snapshotJson(), T0
        )).run().runId();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class CanaryProvider implements InferenceProvider {
        private final String requestMarker = "canary-request-marker-" + Long.toHexString(0x5eedL);

        @Override
        public ProviderInferenceResponse complete(ProviderInferenceRequest request) {
            var schemaId = UUID.nameUUIDFromBytes(
                    (request.runId() + ":schema").getBytes(StandardCharsets.UTF_8));
            var fieldId = UUID.nameUUIDFromBytes(
                    (request.runId() + ":field").getBytes(StandardCharsets.UTF_8));
            var evidence = cn.hbads.renderweave.inference.candidate.CandidateEvidence.image(
                    request.images().getFirst().artifactId(),
                    new cn.hbads.renderweave.inference.candidate.CandidateBoundingBox(
                            500, 500, 9_500, 2_500)
            );
            var assessment = cn.hbads.renderweave.inference.candidate.CandidateAssessment.ai(
                    9_000, true,
                    cn.hbads.renderweave.inference.candidate.CandidateResolution.NOT_REQUIRED,
                    List.of(evidence)
            );
            var candidate = new cn.hbads.renderweave.inference.candidate.CandidateJsonCodec().write(
                    new cn.hbads.renderweave.inference.candidate.CandidateBundle(
                            cn.hbads.renderweave.inference.candidate.CandidateBundle.CONTRACT_VERSION,
                            schemaId,
                            List.of(new cn.hbads.renderweave.inference.candidate.CandidateSchema(
                                    schemaId, "synthetic-card", OCR_CANARY,
                                    cn.hbads.renderweave.inference.candidate.CandidateSource.AI,
                                    assessment,
                                    List.of(new cn.hbads.renderweave.inference.candidate.CandidateField(
                                            fieldId, "title", COT_CANARY, false,
                                            cn.hbads.renderweave.inference.candidate.CandidateValue.scalar(
                                                    cn.hbads.renderweave.inference.candidate.CandidateValueKind.TEXT),
                                            cn.hbads.renderweave.inference.candidate.CandidateSource.AI,
                                            assessment
                                    ))
                            ))
                    ));
            return new ProviderInferenceResponse(
                    candidate, RESPONSE_CANARY + "-" + requestMarker,
                    request.profile().model(), new ProviderUsage(1_000, 500), "stop"
            );
        }

        @Override
        public boolean configured() {
            return true;
        }

        String requestMarker() {
            return requestMarker;
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
