package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.certification.AuthorizationStatus;
import cn.hbads.renderweave.inference.certification.AuthorizedCertificationCase;
import cn.hbads.renderweave.inference.certification.CertificationAuthorityInventory;
import cn.hbads.renderweave.inference.certification.CertificationCanaryCase;
import cn.hbads.renderweave.inference.certification.CertificationStage;
import cn.hbads.renderweave.inference.certification.CertificationStageExecutionService;
import cn.hbads.renderweave.inference.certification.CertificationStageLedgerStatus;
import cn.hbads.renderweave.inference.certification.CertificationStageLedgerViolation;
import cn.hbads.renderweave.inference.certification.CertificationInferenceProvider;
import cn.hbads.renderweave.inference.certification.FrozenCertificationCycle;
import cn.hbads.renderweave.inference.certification.FrozenImageOnlyCertificationManifest;
import cn.hbads.renderweave.inference.certification.ImageOnlyCertificationAuthorization;
import cn.hbads.renderweave.inference.certification.ImageOnlyCertificationManifestFactory;
import cn.hbads.renderweave.inference.certification.ProfileCertificationEvent;
import cn.hbads.renderweave.inference.certification.ProfileCertificationService;
import cn.hbads.renderweave.inference.certification.ProfileCertificationStore;
import cn.hbads.renderweave.inference.certification.ProfileSuccessorDiagnosticManifest;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;
import cn.hbads.renderweave.inference.provider.ProviderCostEstimator;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderInferenceResponse;
import cn.hbads.renderweave.inference.provider.ProviderUsage;
import cn.hbads.renderweave.inference.run.InferenceStage;
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
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class PostgresCertificationStageExecutionStoreTest {
    private static final Instant T0 = Instant.parse("2026-08-17T09:00:00Z");
    private static final String PROFILE_SHA =
            "a9fe98e1cfa4b7cc126db1f74601fdebe60526a1c999924daf189ed5f1ac5eb0";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PostgresCertificationStageExecutionStore store;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearLedger() {
        jdbcClient.sql("truncate table certification_stage_call_reservation, "
                + "certification_stage_run, certification_stage_case, "
                + "certification_stage_ledger").update();
    }

    @Test
    void exactJ1BecomesAPerCallPermitOnlyAfterDurableReservation() {
        var fixture = fixture(60, 500_000, 10_000_000);
        var authority = new CertificationStageExecutionService(store);
        authority.openStage(fixture.authorization(), fixture.cycle(), fixture.manifest(),
                fixture.progress(), T0.plusSeconds(1));

        var runId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var firstCase = fixture.authorization().cases().getFirst();
        authority.startRun(fixture.authorization().authorizationId(), runId, firstCase,
                T0.plusSeconds(2));
        var permit = authority.reserveCall(fixture.authorization().authorizationId(), runId,
                0, 100_000, 2_000_000, T0.plusSeconds(3));

        assertThat(permit.grantsProviderEgress()).isTrue();
        assertThat(permit.authorizationId()).isEqualTo(fixture.authorization().authorizationId());
        assertThat(permit.caseId()).isEqualTo(firstCase.caseId());
        assertThat(authority.snapshot(fixture.authorization().authorizationId()))
                .satisfies(snapshot -> {
                    assertThat(snapshot.status()).isEqualTo(CertificationStageLedgerStatus.OPEN);
                    assertThat(snapshot.startedRuns()).isEqualTo(1);
                    assertThat(snapshot.providerCalls()).isEqualTo(1);
                    assertThat(snapshot.exposedModelTokens()).isEqualTo(100_000);
                    assertThat(snapshot.exposedCostMicrosCny()).isEqualTo(2_000_000);
                    assertThat(snapshot.unsettledReservations()).isEqualTo(1);
                });

        authority.settleCall(permit.reservationId(), 40_000, 1_000_000,
                T0.plusSeconds(4));
        assertThat(authority.snapshot(fixture.authorization().authorizationId()))
                .satisfies(snapshot -> {
                    assertThat(snapshot.exposedModelTokens()).isEqualTo(40_000);
                    assertThat(snapshot.exposedCostMicrosCny()).isEqualTo(1_000_000);
                    assertThat(snapshot.unsettledReservations()).isZero();
                });

        authority.closeStage(fixture.authorization().authorizationId(),
                "CANARY_STAGE_COMPLETED", T0.plusSeconds(5));
        assertThat(authority.snapshot(fixture.authorization().authorizationId()).status())
                .isEqualTo(CertificationStageLedgerStatus.CLOSED);
        assertReason("CERTIFICATION_STAGE_LEDGER_NOT_OPEN", () -> authority.reserveCall(
                fixture.authorization().authorizationId(), runId, 1,
                1, 1, T0.plusSeconds(6)));
    }

    @Test
    void callTokenCostRunCaseAndTimeBoundsFailClosed() {
        var fixture = fixture(1, 100, 1_000);
        var authority = new CertificationStageExecutionService(store);
        authority.openStage(fixture.authorization(), fixture.cycle(), fixture.manifest(),
                fixture.progress(), T0.plusSeconds(1));
        var firstRun = UUID.fromString("20000000-0000-0000-0000-000000000001");
        var secondRun = UUID.fromString("20000000-0000-0000-0000-000000000002");
        var firstCase = fixture.authorization().cases().getFirst();
        var secondCase = fixture.authorization().cases().get(1);
        authority.startRun(fixture.authorization().authorizationId(), firstRun, firstCase,
                T0.plusSeconds(2));

        assertReason("CERTIFICATION_STAGE_CASE_ALREADY_STARTED", () -> authority.startRun(
                fixture.authorization().authorizationId(), secondRun, firstCase,
                T0.plusSeconds(2)));
        assertReason("CERTIFICATION_STAGE_CASE_MISMATCH", () -> authority.startRun(
                fixture.authorization().authorizationId(), secondRun,
                new AuthorizedCertificationCase(secondCase.caseId(), "f".repeat(64)),
                T0.plusSeconds(2)));

        authority.startRun(fixture.authorization().authorizationId(), secondRun, secondCase,
                T0.plusSeconds(2));
        authority.reserveCall(fixture.authorization().authorizationId(), firstRun,
                0, 100, 1_000, T0.plusSeconds(3));
        assertReason("CERTIFICATION_STAGE_CALL_BUDGET_EXHAUSTED", () -> authority.reserveCall(
                fixture.authorization().authorizationId(), secondRun,
                0, 1, 1, T0.plusSeconds(3)));

        var expired = fixture(60, 500_000, 10_000_000);
        var expiredAuthority = new CertificationStageExecutionService(store);
        expiredAuthority.openStage(expired.authorization(), expired.cycle(), expired.manifest(),
                expired.progress(), T0.plusSeconds(1));
        assertReason("CERTIFICATION_STAGE_LEDGER_EXPIRED", () -> expiredAuthority.startRun(
                expired.authorization().authorizationId(), UUID.randomUUID(),
                expired.authorization().cases().getFirst(), T0.plusSeconds(4 * 60 * 60)));
    }

    @Test
    void providerBytesCannotLeaveBeforeTheCertificationReservationCommits() {
        var fixture = fixture(60, 500_000, 10_000_000);
        var authority = new CertificationStageExecutionService(store);
        authority.openStage(fixture.authorization(), fixture.cycle(), fixture.manifest(),
                fixture.progress(), T0.plusSeconds(1));
        var runId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        authority.startRun(fixture.authorization().authorizationId(), runId,
                fixture.authorization().cases().getFirst(), T0.plusSeconds(2));
        var delegate = new ObservingProvider(() -> {
            var duringCall = authority.snapshot(fixture.authorization().authorizationId());
            assertThat(duringCall.providerCalls()).isEqualTo(1);
            assertThat(duringCall.unsettledReservations()).isEqualTo(1);
        });
        var provider = new CertificationInferenceProvider(
                delegate, authority, fixture.authorization().authorizationId(),
                Clock.fixed(T0.plusSeconds(3), ZoneOffset.UTC));
        var profile = new InferenceProfileRegistry().require(
                ProfileRunBudgetPolicy.IMAGE_ONLY_V47_PROFILE_ID).profile();
        var request = new ProviderInferenceRequest(
                runId, 0, InferenceStage.OBSERVE, profile,
                "Return JSON only.", "{}", List.of());

        var response = provider.complete(request);

        assertThat(response.usage()).isEqualTo(new ProviderUsage(1_000, 500));
        assertThat(delegate.calls).isEqualTo(1);
        var snapshot = authority.snapshot(fixture.authorization().authorizationId());
        assertThat(snapshot.providerCalls()).isEqualTo(1);
        assertThat(snapshot.exposedModelTokens()).isEqualTo(1_500);
        assertThat(snapshot.exposedCostMicrosCny()).isEqualTo(
                ProviderCostEstimator.estimateMicrosCny(profile, response.usage()));
        assertThat(snapshot.unsettledReservations()).isZero();

        authority.closeStage(fixture.authorization().authorizationId(),
                "CANARY_STAGE_STOPPED", T0.plusSeconds(4));
        assertReason("CERTIFICATION_STAGE_LEDGER_NOT_OPEN", () -> provider.complete(
                new ProviderInferenceRequest(
                        runId, 1, InferenceStage.OBSERVE, profile,
                        "Return JSON only.", "{}", List.of())));
        assertThat(delegate.calls).isEqualTo(1);
    }

    @Test
    void nonScoringSuccessorDiagnosticUsesItsExactOneRunHardCaps() {
        var cycleId = UUID.fromString("47000000-0000-0000-0000-000000000001");
        var diagnosticCase = new AuthorizedCertificationCase(
                "v46-failed-route-82",
                ProfileSuccessorDiagnosticManifest.V46_FAILED_ARTIFACT_SHA256
        );
        var manifest = ProfileSuccessorDiagnosticManifest.create(
                cycleId, PROFILE_SHA,
                ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION + ":" + "a".repeat(64),
                diagnosticCase, T0
        );
        var authorization = new ImageOnlyCertificationAuthorization(
                ImageOnlyCertificationAuthorization.VERSION,
                "iopa-v47-diagnostic-ledger", AuthorizationStatus.OPEN,
                cycleId, CertificationStage.PROFILE_SUCCESSOR_DIAGNOSTIC_1,
                manifest.profileId(), manifest.profileSha256(), manifest.manifestIdentity(),
                manifest.evaluatorIdentity(), manifest.normalizationIdentity(),
                "DASHSCOPE", "qwen3.8-max",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                manifest.inputProvenance(), manifest.dataClassification(), List.of(diagnosticCase),
                1, 5, 100_000, 3_000_000,
                5, 3_000_000,
                T0, T0.plusSeconds(2 * 60 * 60), "owner:renderweave", T0,
                "IMAGE_ONLY_PROFILE_SUCCESSOR_DIAGNOSTIC_1", null, null
        );
        var authority = new CertificationStageExecutionService(store);
        authority.openProfileSuccessorDiagnostic(authorization, manifest, T0.plusSeconds(1));
        var runId = UUID.fromString("47000000-0000-0000-0000-000000000002");
        authority.startRun(authorization.authorizationId(), runId, diagnosticCase,
                T0.plusSeconds(2));
        for (var attempt = 0; attempt < 5; attempt++) {
            authority.reserveCall(authorization.authorizationId(), runId, attempt,
                    1, 1, T0.plusSeconds(3 + attempt));
        }

        assertReason("CERTIFICATION_STAGE_CALL_BUDGET_EXHAUSTED", () ->
                authority.reserveCall(authorization.authorizationId(), runId, 5,
                        1, 1, T0.plusSeconds(9)));
        assertThat(authority.snapshot(authorization.authorizationId())).satisfies(snapshot -> {
            assertThat(snapshot.stage()).isEqualTo(
                    CertificationStage.PROFILE_SUCCESSOR_DIAGNOSTIC_1);
            assertThat(snapshot.profileId()).isEqualTo(
                    ProfileRunBudgetPolicy.IMAGE_ONLY_V47_PROFILE_ID);
            assertThat(snapshot.maximumRuns()).isEqualTo(1);
            assertThat(snapshot.maximumProviderCalls()).isEqualTo(5);
            assertThat(snapshot.maximumModelTokens()).isEqualTo(100_000);
            assertThat(snapshot.maximumCostMicrosCny()).isEqualTo(3_000_000);
        });
    }

    @Test
    void v49ScriptedProviderClosesReviewAndNegativeDiagnosticPathsWithoutReservations() {
        exerciseV49DiagnosticClosure(
                "iopa-v49-diagnostic-review",
                UUID.fromString("49000000-0000-0000-0000-000000000001"),
                UUID.fromString("49000000-0000-0000-0000-000000000002"),
                "PROFILE_SUCCESSOR_DIAGNOSTIC_REVIEW_REQUIRED");
        exerciseV49DiagnosticClosure(
                "iopa-v49-diagnostic-negative",
                UUID.fromString("49000000-0000-0000-0000-000000000003"),
                UUID.fromString("49000000-0000-0000-0000-000000000004"),
                "PROFILE_SUCCESSOR_DIAGNOSTIC_FAILED_VISUAL_GROUNDING_REGION_UNCLASSIFIED");
    }

    private void exerciseV49DiagnosticClosure(
            String authorizationId,
            UUID cycleId,
            UUID runId,
            String closureReason
    ) {
        var diagnosticCase = new AuthorizedCertificationCase(
                "v46-failed-route-82",
                ProfileSuccessorDiagnosticManifest.V46_FAILED_ARTIFACT_SHA256);
        var manifest = ProfileSuccessorDiagnosticManifest.createForProfile(
                ProfileRunBudgetPolicy.IMAGE_ONLY_V49_PROFILE_ID,
                cycleId,
                "acffdd4dd56ca2f1f7260fc5d37aa48ca3da488a0ae2718f2095bf1530e86eaf",
                ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION + ":" + "a".repeat(64),
                diagnosticCase,
                T0);
        var authorization = new ImageOnlyCertificationAuthorization(
                ImageOnlyCertificationAuthorization.VERSION,
                authorizationId,
                AuthorizationStatus.OPEN,
                cycleId,
                CertificationStage.PROFILE_SUCCESSOR_DIAGNOSTIC_1,
                manifest.profileId(),
                manifest.profileSha256(),
                manifest.manifestIdentity(),
                manifest.evaluatorIdentity(),
                manifest.normalizationIdentity(),
                "DASHSCOPE",
                "qwen3.8-max",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                manifest.inputProvenance(),
                manifest.dataClassification(),
                List.of(diagnosticCase),
                1, 5, 100_000, 3_000_000,
                5, 3_000_000,
                T0, T0.plusSeconds(2 * 60 * 60), "owner:renderweave", T0,
                "IMAGE_ONLY_PROFILE_SUCCESSOR_DIAGNOSTIC_1", null, null);
        var authority = new CertificationStageExecutionService(store);
        authority.openProfileSuccessorDiagnostic(authorization, manifest, T0.plusSeconds(1));
        authority.startRun(authorizationId, runId, diagnosticCase, T0.plusSeconds(2));
        var delegate = new ObservingProvider(() -> assertThat(
                authority.snapshot(authorizationId).unsettledReservations()).isEqualTo(1));
        var provider = new CertificationInferenceProvider(
                delegate, authority, authorizationId,
                Clock.fixed(T0.plusSeconds(3), ZoneOffset.UTC));
        var profile = new InferenceProfileRegistry()
                .require(ProfileRunBudgetPolicy.IMAGE_ONLY_V49_PROFILE_ID).profile();

        provider.complete(new ProviderInferenceRequest(
                runId, 0, InferenceStage.OBSERVE, profile,
                "Return JSON only.", "{}", List.of()));
        authority.closeStage(authorizationId, closureReason, T0.plusSeconds(4));

        assertThat(authority.snapshot(authorizationId)).satisfies(snapshot -> {
            assertThat(snapshot.status()).isEqualTo(CertificationStageLedgerStatus.CLOSED);
            assertThat(snapshot.profileId()).isEqualTo(
                    ProfileRunBudgetPolicy.IMAGE_ONLY_V49_PROFILE_ID);
            assertThat(snapshot.startedRuns()).isEqualTo(1);
            assertThat(snapshot.providerCalls()).isEqualTo(1);
            assertThat(snapshot.exposedModelTokens()).isEqualTo(1_500);
            assertThat(snapshot.exposedCostMicrosCny()).isPositive();
            assertThat(snapshot.unsettledReservations()).isZero();
            assertThat(snapshot.closureReason()).isEqualTo(closureReason);
        });
        assertReason("CERTIFICATION_STAGE_LEDGER_NOT_OPEN", () -> provider.complete(
                new ProviderInferenceRequest(
                        runId, 1, InferenceStage.OBSERVE, profile,
                        "Return JSON only.", "{}", List.of())));
        assertThat(delegate.calls).isEqualTo(1);
    }

    private static Fixture fixture(int maximumCalls, long maximumTokens, long maximumCost) {
        var canaries = new ArrayList<CertificationCanaryCase>();
        for (var index = 1; index <= 5; index++) {
            canaries.add(new CertificationCanaryCase(
                    "owner-canary-ledger-" + index, String.format("%064x", index)));
        }
        var manifest = new ImageOnlyCertificationManifestFactory().create(
                ProfileRunBudgetPolicy.IMAGE_ONLY_V47_PROFILE_ID,
                PROFILE_SHA, canaries, "image-only-certification-seed-v1");
        var cycleId = UUID.randomUUID();
        var cycle = new FrozenCertificationCycle(
                cycleId, manifest.profileId(), manifest.profileSha256(),
                manifest.manifestIdentity(), manifest.evaluatorIdentity(),
                CertificationAuthorityInventory.loadCanonical().canonicalSha256(), T0);
        var certification = new ProfileCertificationService(new MemoryCertificationStore());
        certification.start(cycle, manifest);
        var authorization = new ImageOnlyCertificationAuthorization(
                ImageOnlyCertificationAuthorization.VERSION,
                "iopa-canary-ledger-" + cycleId.toString().substring(0, 8),
                AuthorizationStatus.OPEN, cycleId, CertificationStage.CANARY_5,
                manifest.profileId(), manifest.profileSha256(), manifest.manifestIdentity(),
                manifest.evaluatorIdentity(), null, "DASHSCOPE", "qwen3.8-max",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "USER_PROVIDED", "ORDINARY_DESIGN",
                canaries.stream().map(item -> new AuthorizedCertificationCase(
                        item.caseId(), item.artifactSha256())).toList(),
                5, maximumCalls, maximumTokens, maximumCost,
                12, 6_000_000L,
                T0, T0.plusSeconds(4 * 60 * 60), "owner:renderweave", T0,
                "IMAGE_ONLY_PROFILE_CERTIFICATION_CANARY_5", null, null);
        return new Fixture(manifest, cycle, authorization,
                certification.progress(cycleId));
    }

    private static void assertReason(String expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(CertificationStageLedgerViolation.class,
                        failure -> assertThat(failure.reasonCode()).isEqualTo(expected));
    }

    private record Fixture(
            FrozenImageOnlyCertificationManifest manifest,
            FrozenCertificationCycle cycle,
            ImageOnlyCertificationAuthorization authorization,
            cn.hbads.renderweave.inference.certification.ProfileCertificationProgress progress
    ) { }

    private static final class MemoryCertificationStore implements ProfileCertificationStore {
        private final Map<UUID, List<ProfileCertificationEvent>> events = new HashMap<>();

        @Override
        public void append(ProfileCertificationEvent event) {
            events.computeIfAbsent(event.cycleId(), ignored -> new ArrayList<>()).add(event);
        }

        @Override
        public List<ProfileCertificationEvent> events(UUID cycleId) {
            return List.copyOf(events.getOrDefault(cycleId, List.of()));
        }
    }

    private static final class ObservingProvider implements InferenceProvider {
        private final Runnable beforeResponse;
        private int calls;

        private ObservingProvider(Runnable beforeResponse) {
            this.beforeResponse = beforeResponse;
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public ProviderInferenceResponse complete(ProviderInferenceRequest request) {
            calls++;
            beforeResponse.run();
            return new ProviderInferenceResponse(
                    "{}", "fake-certification-request", request.profile().model(),
                    new ProviderUsage(1_000, 500), "stop");
        }
    }
}
