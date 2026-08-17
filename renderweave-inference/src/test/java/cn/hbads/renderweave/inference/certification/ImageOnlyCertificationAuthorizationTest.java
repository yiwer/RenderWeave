package cn.hbads.renderweave.inference.certification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageOnlyCertificationAuthorizationTest {
    private static final Instant T0 = Instant.parse("2026-08-17T08:00:00Z");
    private static final String SHA = "22f561c88b30fabbf3ba660bcfe203fb570975f770ff122f2ce1c7216454ac0c";
    private static final UUID CYCLE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void exactOpenJ1ProducesOnlyAProviderZeroPreflightPermit() {
        var manifest = manifest();
        var cycle = cycle(manifest);
        var authorization = authorization(manifest, AuthorizationStatus.OPEN,
                CertificationStage.CANARY_5, SHA, "ORDINARY_DESIGN", 5, 60,
                1_000_000, 30_000_000, T0, T0.plusSeconds(48 * 60 * 60),
                authorizedCases(manifest, CertificationStage.CANARY_5));

        var permit = new ImageOnlyCertificationPreflight().requirePermit(
                authorization, cycle, manifest,
                ProfileCertificationStatus.IN_PROGRESS, T0.plusSeconds(1));

        assertEquals(authorization.authorizationId(), permit.authorizationId());
        assertEquals(0, permit.providerAttempts());
        assertEquals(0, permit.providerReservations());
        assertEquals(0, permit.providerCostMicrosCny());
        assertEquals(0, permit.apiKeyReads());
    }

    @Test
    void missingExpiredWrongIdentityClassCaseAndBoundsAllFailClosed() {
        var manifest = manifest();
        var cycle = cycle(manifest);
        var preflight = new ImageOnlyCertificationPreflight();
        var cases = authorizedCases(manifest, CertificationStage.CANARY_5);
        assertReason("CERTIFICATION_AUTHORIZATION_REQUIRED", () -> preflight.requirePermit(
                null, cycle, manifest, ProfileCertificationStatus.IN_PROGRESS, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_NOT_OPEN", () -> preflight.requirePermit(
                authorization(manifest, AuthorizationStatus.CLOSED, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(1), cases),
                cycle, manifest, ProfileCertificationStatus.IN_PROGRESS, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_EXPIRED", () -> preflight.requirePermit(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0.minusSeconds(100), T0.minusSeconds(1), cases),
                cycle, manifest, ProfileCertificationStatus.IN_PROGRESS, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_PROFILE_MISMATCH", () -> preflight.requirePermit(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        "f".repeat(64), "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, ProfileCertificationStatus.IN_PROGRESS, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_DATA_CLASS_NOT_ALLOWED", () -> preflight.requirePermit(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "CONFIDENTIAL", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, ProfileCertificationStatus.IN_PROGRESS, T0));
        var wrongCases = new ArrayList<>(cases);
        wrongCases.set(0, new AuthorizedCertificationCase("owner-canary-1", "f".repeat(64)));
        assertReason("CERTIFICATION_AUTHORIZATION_CASE_SET_MISMATCH", () -> preflight.requirePermit(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), wrongCases),
                cycle, manifest, ProfileCertificationStatus.IN_PROGRESS, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_RUN_CAP_INVALID", () -> preflight.requirePermit(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 6, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, ProfileCertificationStatus.IN_PROGRESS, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_CALL_CAP_INVALID", () -> preflight.requirePermit(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 61, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, ProfileCertificationStatus.IN_PROGRESS, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_TOKEN_CAP_INVALID", () -> preflight.requirePermit(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_001, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, ProfileCertificationStatus.IN_PROGRESS, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_COST_CAP_INVALID", () -> preflight.requirePermit(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_001,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, ProfileCertificationStatus.IN_PROGRESS, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_DURATION_INVALID", () -> preflight.requirePermit(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(48 * 60 * 60 + 1), cases),
                cycle, manifest, ProfileCertificationStatus.IN_PROGRESS, T0));
        assertReason("CERTIFICATION_AUTHORIZATION_CYCLE_TERMINAL", () -> preflight.requirePermit(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                cycle, manifest, ProfileCertificationStatus.FAILED, T0));

        var wrongCycle = new FrozenCertificationCycle(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                manifest.profileId(), manifest.profileSha256(), manifest.manifestIdentity(),
                manifest.evaluatorIdentity(), T0.minusSeconds(1));
        assertReason("CERTIFICATION_AUTHORIZATION_CYCLE_MISMATCH", () -> preflight.requirePermit(
                authorization(manifest, AuthorizationStatus.OPEN, CertificationStage.CANARY_5,
                        SHA, "ORDINARY_DESIGN", 5, 60, 1_000_000, 30_000_000,
                        T0, T0.plusSeconds(10), cases),
                wrongCycle, manifest, ProfileCertificationStatus.IN_PROGRESS, T0));
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
                "dashscope-qwen38-max-product-v46-hybrid-generic", profileSha,
                manifest.manifestIdentity(), manifest.evaluatorIdentity(),
                "DASHSCOPE", "qwen3.8-max",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "USER_PROVIDED", dataClass, cases,
                maxRuns, maxCalls, maxTokens, maxCost,
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
                SHA, canaries, "image-only-certification-seed-v1");
    }

    private static FrozenCertificationCycle cycle(FrozenImageOnlyCertificationManifest manifest) {
        return new FrozenCertificationCycle(
                CYCLE_ID, manifest.profileId(), manifest.profileSha256(), manifest.manifestIdentity(),
                manifest.evaluatorIdentity(), T0.minusSeconds(1));
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
}
