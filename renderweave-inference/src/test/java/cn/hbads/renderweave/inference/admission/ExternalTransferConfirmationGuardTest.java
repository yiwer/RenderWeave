package cn.hbads.renderweave.inference.admission;

import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.input.NormalizedInput;
import cn.hbads.renderweave.inference.input.NormalizedInputReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalTransferConfirmationGuardTest {
    private static final Instant T0 = Instant.parse("2026-08-18T08:00:00Z");
    private final ExternalTransferConfirmationGuard guard = new ExternalTransferConfirmationGuard();

    @Test
    void admissionIdentitiesMatchIndependentKnownAnswerVector() {
        var notice = ExternalTransferNotice.issue(
                "renderweave-external-transfer-notice/1.0", "zh-CN", "provider-legal",
                "DASHSCOPE", "qwen3.8-max",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                "cn-beijing", "schema-candidate", "unknown", "unknown", "possible",
                "profile-1", "1".repeat(64), 12, 6_000_000, 604_800,
                "policy/1", "2".repeat(64), "contract/1", "3".repeat(64)
        );
        var manifest = new LiveInputManifest(
                LiveInputManifest.VERSION,
                "0835aae49cbbab6684a17c5e7f45f7724ee91a0c1b3cc7b85c3c1ed2889eafb8",
                64,
                List.of(new LiveInputManifest.Item(0, "4".repeat(64), "image/png", 64, 4, 4))
        );

        assertEquals("733df81318c7ae0c7857da64f9e0f970c5a9542b3c7e5a0236b4307bdc2ac682",
                notice.contentSha256());
        assertEquals("f09805d01fe77fdb1047393a362fe15dc5364dff24bf98827c1a6bf25f2f87ea",
                ExternalTransferConfirmation.requestFingerprint(
                        "actor-opaque-001", InputProvenance.USER_PROVIDED,
                        SensitivityClass.ORDINARY_DESIGN, notice, manifest
                ));
    }

    @Test
    void exactFifteenMinuteAndTwoHourBoundariesAreClosedWithoutAssertionSkew() {
        var fixture = fixture(T0, "request-1", "jti-1");

        assertDoesNotThrow(() -> authorize(
                fixture, false, ExternalTransferConfirmationGuard.ProviderAttemptKnowledge.CLEAR,
                T0.plusSeconds(15 * 60)
        ));
        assertCode("LIVE_CONFIRMATION_EXPIRED", () -> authorize(
                fixture, false, ExternalTransferConfirmationGuard.ProviderAttemptKnowledge.CLEAR,
                T0.plusSeconds(15 * 60).plusNanos(1)
        ));
        assertDoesNotThrow(() -> authorize(
                fixture, true, ExternalTransferConfirmationGuard.ProviderAttemptKnowledge.CLEAR,
                T0.plusSeconds(2 * 60 * 60)
        ));
        assertCode("LIVE_PROVIDER_CALL_WINDOW_EXPIRED", () -> authorize(
                fixture, true, ExternalTransferConfirmationGuard.ProviderAttemptKnowledge.CLEAR,
                T0.plusSeconds(2 * 60 * 60).plusNanos(1)
        ));
    }

    @Test
    void noticeDriftBlocksOnlyUndispatchedConfirmationAndAmbiguousAttemptNeverReplays() {
        var fixture = fixture(T0, "request-1", "jti-1");
        var stale = new ExternalTransferNotice.Identity(
                fixture.configuration.notice().version(), fixture.configuration.notice().locale(),
                "f".repeat(64)
        );
        assertCode("LIVE_TRANSFER_NOTICE_STALE", () -> guard.authorizeProviderRequest(
                fixture.confirmation, stale, fixture.confirmation.profileSha256(), fixture.manifest,
                false, ExternalTransferConfirmationGuard.ProviderAttemptKnowledge.CLEAR, T0
        ));
        assertDoesNotThrow(() -> guard.authorizeProviderRequest(
                fixture.confirmation, stale, fixture.confirmation.profileSha256(), fixture.manifest,
                true, ExternalTransferConfirmationGuard.ProviderAttemptKnowledge.CLEAR, T0.plusSeconds(901)
        ));
        assertCode("LIVE_PROVIDER_ATTEMPT_AMBIGUOUS", () -> authorize(
                fixture, true, ExternalTransferConfirmationGuard.ProviderAttemptKnowledge.AMBIGUOUS,
                T0.plusSeconds(901)
        ));
    }

    @Test
    void manifestAndProfileDriftFailClosed() {
        var fixture = fixture(T0, "request-1", "jti-1");
        assertCode("LIVE_PROFILE_IDENTITY_MISMATCH", () -> guard.authorizeProviderRequest(
                fixture.confirmation, fixture.configuration.notice().identity(), "f".repeat(64),
                fixture.manifest, false,
                ExternalTransferConfirmationGuard.ProviderAttemptKnowledge.CLEAR, T0
        ));
        var otherManifest = manifest("2".repeat(64));
        assertCode("LIVE_INPUT_MANIFEST_MISMATCH", () -> guard.authorizeProviderRequest(
                fixture.confirmation, fixture.configuration.notice().identity(),
                fixture.confirmation.profileSha256(), otherManifest, false,
                ExternalTransferConfirmationGuard.ProviderAttemptKnowledge.CLEAR, T0
        ));
    }

    @Test
    void responseLossFingerprintIgnoresFreshRequestIdentityAndTimeButNotActorOrTerms() {
        var first = fixture(T0, "request-1", "jti-1");
        var replay = fixture(T0.plusSeconds(30), "request-2", "jti-2");
        assertEquals(first.confirmation.requestFingerprint(), replay.confirmation.requestFingerprint());
        assertNotEquals(first.confirmation.confirmationId(), replay.confirmation.confirmationId());
        assertNotEquals(first.confirmation.requestId(), replay.confirmation.requestId());

        var otherActor = ExternalTransferConfirmation.issue(
                UUID.randomUUID(), UUID.randomUUID(), identity("other-actor", "request-3", "jti-3"),
                InputProvenance.USER_PROVIDED, SensitivityClass.ORDINARY_DESIGN,
                first.configuration, first.manifest, T0.plusSeconds(30)
        );
        assertNotEquals(first.confirmation.requestFingerprint(), otherActor.requestFingerprint());
    }

    private void authorize(
            Fixture fixture,
            boolean dispatched,
            ExternalTransferConfirmationGuard.ProviderAttemptKnowledge knowledge,
            Instant now
    ) {
        guard.authorizeProviderRequest(
                fixture.confirmation, fixture.configuration.notice().identity(),
                fixture.confirmation.profileSha256(), fixture.manifest,
                dispatched, knowledge, now
        );
    }

    private static Fixture fixture(Instant confirmedAt, String requestId, String jti) {
        var configuration = ImageOnlyProductionAdmissionTest.configuration();
        var manifest = manifest("1".repeat(64));
        var confirmation = ExternalTransferConfirmation.issue(
                UUID.randomUUID(), UUID.randomUUID(), identity("actor-opaque-001", requestId, jti),
                InputProvenance.USER_PROVIDED, SensitivityClass.ORDINARY_DESIGN,
                configuration, manifest, confirmedAt
        );
        return new Fixture(configuration, manifest, confirmation);
    }

    private static GatewayRequestIdentity identity(String actor, String requestId, String jti) {
        return new GatewayRequestIdentity(
                actor, requestId, jti, "POST", ImageOnlyProductionAdmission.LIVE_PATH,
                GatewayAssertionAuthority.idempotencyKeyDigest("idem"),
                T0, T0.plusSeconds(60), "gateway-2026-08-a"
        );
    }

    private static LiveInputManifest manifest(String artifactId) {
        var artifact = new NormalizedArtifact(
                artifactId, NormalizedArtifact.Kind.IMAGE, "memory:" + artifactId,
                "image/png", 64, 4, 4
        );
        var input = new NormalizedInput(
                InferenceMode.IMAGE_ONLY, "profile", "production-live", "0".repeat(64),
                List.of(artifact),
                List.of(new NormalizedInputReference(NormalizedArtifact.Kind.IMAGE, 0, artifactId)),
                List.of()
        );
        return LiveInputManifest.from(input);
    }

    private static void assertCode(String expected, Runnable call) {
        var problem = assertThrows(LiveAdmissionProblem.class, call::run);
        assertEquals(expected, problem.code());
    }

    private record Fixture(
            LiveAdmissionConfiguration configuration,
            LiveInputManifest manifest,
            ExternalTransferConfirmation confirmation
    ) { }
}
