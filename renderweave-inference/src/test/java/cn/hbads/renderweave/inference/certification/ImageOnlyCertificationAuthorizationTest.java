package cn.hbads.renderweave.inference.certification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageOnlyCertificationAuthorizationTest {
    private static final Instant T0 = Instant.parse("2026-08-17T08:00:00Z");
    private static final String SHA = "a9fe98e1cfa4b7cc126db1f74601fdebe60526a1c999924daf189ed5f1ac5eb0";
    private static final UUID CYCLE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void exactOpenJ1ProducesOnlyANonEgressProviderZeroProof() {
        var manifest = manifest();
        var cycle = cycle(manifest);
        var service = started(cycle, manifest);
        var authorization = authorization(manifest, AuthorizationStatus.OPEN,
                CertificationStage.CANARY_5, SHA, "ORDINARY_DESIGN", 5, 60,
                1_000_000, 30_000_000, T0, T0.plusSeconds(48 * 60 * 60),
                authorizedCases(manifest, CertificationStage.CANARY_5));

        var proof = new ImageOnlyCertificationPreflight().requireProviderZeroProof(
                authorization, cycle, manifest, service.progress(cycle.cycleId()),
                T0.plusSeconds(1));

        assertEquals(authorization.authorizationId(), proof.authorizationId());
        assertEquals(0, proof.providerAttempts());
        assertEquals(0, proof.providerReservations());
        assertEquals(0, proof.providerCostMicrosCny());
        assertEquals(0, proof.apiKeyReads());
        assertFalse(proof.grantsProviderEgress());
    }

    @Test
    void missingExpiredWrongIdentityClassCaseAndBoundsAllFailClosed() {
        var manifest = manifest();
        var cycle = cycle(manifest);
        var service = started(cycle, manifest);
        var progress = service.progress(cycle.cycleId());
        var preflight = new ImageOnlyCertificationPreflight();
        var cases = authorizedCases(manifest, CertificationStage.CANARY_5);
        assertReason("CERTIFICATION_AUTHORIZATION_REQUIRED", () -> preflight.requireProviderZeroProof(
                null, cycle, manifest, progress, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_NOT_OPEN", () -> preflight.requireProviderZeroProof(
                authorization(manifest, AuthorizationStatus.CLOSED, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(1), cases),
                cycle, manifest, progress, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_EXPIRED", () -> preflight.requireProviderZeroProof(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0.minusSeconds(100), T0.minusSeconds(1), cases),
                cycle, manifest, progress, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_PROFILE_MISMATCH", () -> preflight.requireProviderZeroProof(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        "f".repeat(64), "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, progress, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_DATA_CLASS_NOT_ALLOWED", () -> preflight.requireProviderZeroProof(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "CONFIDENTIAL", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, progress, T0));
        var wrongCases = new ArrayList<>(cases);
        wrongCases.set(0, new AuthorizedCertificationCase("owner-canary-1", "f".repeat(64)));
        assertReason("CERTIFICATION_AUTHORIZATION_CASE_SET_MISMATCH", () -> preflight.requireProviderZeroProof(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), wrongCases),
                cycle, manifest, progress, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_RUN_CAP_INVALID", () -> preflight.requireProviderZeroProof(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 6, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, progress, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_CALL_CAP_INVALID", () -> preflight.requireProviderZeroProof(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 61, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, progress, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_TOKEN_CAP_INVALID", () -> preflight.requireProviderZeroProof(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_001, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, progress, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_COST_CAP_INVALID", () -> preflight.requireProviderZeroProof(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_001,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, progress, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_DURATION_INVALID", () -> preflight.requireProviderZeroProof(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(48 * 60 * 60 + 1), cases),
                cycle, manifest, progress, T0));
        var staleApproval = withApprovedAt(authorization(
                manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                T0, T0.plusSeconds(10), cases), cycle.createdAt().minusSeconds(1));
        assertReason("CERTIFICATION_AUTHORIZATION_APPROVAL_PREDATES_CYCLE",
                () -> preflight.requireProviderZeroProof(
                        staleApproval, cycle, manifest, progress, T0));

        var oldCycle = cycle(manifest, T0.minusSeconds(49 * 60 * 60));
        var oldService = started(oldCycle, manifest);
        var longApprovalWindow = withApprovedAt(authorization(
                manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                T0, T0.plusSeconds(1), cases), T0.minusSeconds(48 * 60 * 60));
        assertReason("CERTIFICATION_AUTHORIZATION_DURATION_INVALID",
                () -> preflight.requireProviderZeroProof(
                        longApprovalWindow, oldCycle, manifest,
                        oldService.progress(oldCycle.cycleId()), T0));

        var finalCases = authorizedCases(manifest, CertificationStage.FINAL_60);
        assertReason("CERTIFICATION_AUTHORIZATION_STAGE_NOT_UNLOCKED",
                () -> preflight.requireProviderZeroProof(
                        authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.FINAL_60,
                                SHA, "ORDINARY_DESIGN", 60, 720, 1_000_000, 360_000_000,
                                T0, T0.plusSeconds(10), finalCases),
                        cycle, manifest, progress, T0));

        var failedService = started(cycle, manifest);
        failedService.recordStage(cycle.cycleId(), new CertificationStageOutcome(
                CertificationStage.CANARY_5, 4, 5,
                "renderweave-image-only-certification-stage-evidence/1.0:" + "f".repeat(64)),
                T0.plusSeconds(2));
        assertReason("CERTIFICATION_AUTHORIZATION_CYCLE_TERMINAL",
                () -> preflight.requireProviderZeroProof(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, failedService.progress(cycle.cycleId()), T0.plusSeconds(3)));

        var wrongCycle = new FrozenCertificationCycle(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                manifest.profileId(), manifest.profileSha256(), manifest.manifestIdentity(),
                manifest.evaluatorIdentity(),
                CertificationAuthorityInventory.loadCanonical().canonicalSha256(), T0.minusSeconds(1));
        assertReason("CERTIFICATION_AUTHORIZATION_CYCLE_MISMATCH",
                () -> preflight.requireProviderZeroProof(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                wrongCycle, manifest, progress, T0));
    }

    @Test
    void strictCodecRejectsUnknownDuplicateAndNonOpenTemplate() {
        var codec = new ImageOnlyCertificationAuthorizationJsonCodec();
        var manifest = manifest();
        var authorization = authorization(manifest, AuthorizationStatus.OPEN,
                CertificationStage.CANARY_5, SHA, "ORDINARY_DESIGN", 5, 60,
                1_000_000, 30_000_000, T0, T0.plusSeconds(60),
                authorizedCases(manifest, CertificationStage.CANARY_5));
        assertEquals(authorization, codec.read(codec.write(authorization)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.read("{\"version\":\"x\",\"unknown\":true}".getBytes()));
        assertThrows(IllegalArgumentException.class,
                () -> codec.read("{\"version\":\"x\",\"version\":\"y\"}".getBytes()));
    }

    private static ImageOnlyCertificationAuthorization authorization(
            FrozenImageOnlyCertificationManifest manifest,
            AuthorizationStatus status,
            CertificationStage stage,
            String profileSha,
            String dataClass,
            int maxRuns,
            int maxCalls,
            long maxTokens,
            long maxCost,
            Instant effective,
            Instant expires,
            List<AuthorizedCertificationCase> cases
    ) {
        return new ImageOnlyCertificationAuthorization(
                ImageOnlyCertificationAuthorization.VERSION,
                "iopa-j1-test-authorization", status,
                CYCLE_ID, stage,
                "dashscope-qwen38-max-product-v47-hybrid-generic", profileSha,
                manifest.manifestIdentity(), manifest.evaluatorIdentity(),
                null,
                "DASHSCOPE", "qwen3.8-max",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "USER_PROVIDED", dataClass, cases,
                maxRuns, maxCalls, maxTokens, maxCost,
                12, 6_000_000L,
                effective, expires, "owner:renderweave", effective,
                "IMAGE_ONLY_PROFILE_CERTIFICATION_" + stage.name(),
                status == AuthorizationStatus.CLOSED ? effective : null,
                status == AuthorizationStatus.CLOSED ? "TEST_CLOSED" : null
        );
    }

    private static FrozenImageOnlyCertificationManifest manifest() {
        var canaries = new ArrayList<CertificationCanaryCase>();
        for (var index = 1; index <= 5; index++) {
            canaries.add(new CertificationCanaryCase("owner-canary-" + index,
                    String.format("%064x", index)));
        }
        return new ImageOnlyCertificationManifestFactory().create(
                "dashscope-qwen38-max-product-v47-hybrid-generic", SHA, canaries,
                "image-only-certification-seed-v1");
    }

    private static FrozenCertificationCycle cycle(FrozenImageOnlyCertificationManifest manifest) {
        return cycle(manifest, T0.minusSeconds(1));
    }

    private static FrozenCertificationCycle cycle(
            FrozenImageOnlyCertificationManifest manifest,
            Instant createdAt
    ) {
        return new FrozenCertificationCycle(
                CYCLE_ID, manifest.profileId(), manifest.profileSha256(), manifest.manifestIdentity(),
                manifest.evaluatorIdentity(),
                CertificationAuthorityInventory.loadCanonical().canonicalSha256(), createdAt);
    }

    private static ImageOnlyCertificationAuthorization withApprovedAt(
            ImageOnlyCertificationAuthorization source,
            Instant approvedAt
    ) {
        return new ImageOnlyCertificationAuthorization(
                source.version(), source.authorizationId(), source.status(), source.cycleId(),
                source.stage(), source.profileId(), source.profileSha256(), source.manifestIdentity(),
                source.evaluatorIdentity(), source.normalizationIdentity(), source.provider(),
                source.model(), source.providerBaseUrl(),
                source.inputProvenance(), source.dataClassification(), source.cases(),
                source.maximumRuns(), source.maximumProviderCalls(), source.maximumModelTokens(),
                source.maximumCostMicrosCny(), source.maximumProviderCallsPerRun(),
                source.maximumCostPerRunMicrosCny(), source.effectiveAt(), source.expiresAt(),
                source.approvedBy(), approvedAt, source.approvalScope(), source.closedAt(),
                source.closureReason());
    }

    private static ProfileCertificationService started(
            FrozenCertificationCycle cycle,
            FrozenImageOnlyCertificationManifest manifest
    ) {
        var service = new ProfileCertificationService(new MemoryStore());
        service.start(cycle, manifest);
        return service;
    }

    private static List<AuthorizedCertificationCase> authorizedCases(
            FrozenImageOnlyCertificationManifest manifest,
            CertificationStage stage
    ) {
        return manifest.stageView(stage).cases().stream().map(item ->
                new AuthorizedCertificationCase(item.caseId(), item.caseSha256())).toList();
    }

    private static void assertReason(String expected, Runnable action) {
        var failure = assertThrows(CertificationAuthorizationViolation.class, action::run);
        assertEquals(expected, failure.reasonCode());
    }

    private static final class MemoryStore implements ProfileCertificationStore {
        private final Map<UUID, List<ProfileCertificationEvent>> events = new java.util.HashMap<>();

        @Override
        public void append(ProfileCertificationEvent event) {
            events.computeIfAbsent(event.cycleId(), ignored -> new ArrayList<>()).add(event);
        }

        @Override
        public List<ProfileCertificationEvent> events(UUID cycleId) {
            return List.copyOf(events.getOrDefault(cycleId, List.of()));
        }
    }
}
