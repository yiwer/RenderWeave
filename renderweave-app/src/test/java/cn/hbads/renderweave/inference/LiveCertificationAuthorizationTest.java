package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.LiveEvaluationCorpus;
import cn.hbads.renderweave.inference.input.InferenceMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveCertificationAuthorizationTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final String EVALUATION_IDENTITY =
            "renderweave-repository-tree-sha256/1:" + "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void proposedAuthorizationCannotExecute() {
        var authorization = authorization("PROPOSED", List.of(LiveCertificationAuthorization.FLASH_PROFILE),
                180, 3_600_000, null, null, null, null);

        assertThatThrownBy(() -> authorization.requireOpen(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LIVE_CERTIFICATION_AUTHORIZATION_NOT_OPEN");
    }

    @Test
    void openAuthorizationRequiresACompleteCurrentHumanApproval() {
        var incomplete = authorization("OPEN", List.of(LiveCertificationAuthorization.FLASH_PROFILE),
                180, 3_600_000, null, null, null, null);
        var expired = authorization("OPEN", List.of(LiveCertificationAuthorization.FLASH_PROFILE),
                180, 3_600_000, "user", "2026-08-08T06:00:00Z",
                "2026-08-08T07:00:00Z", "60 synthetic flash cases");

        assertThatThrownBy(() -> incomplete.requireOpen(NOW))
                .hasMessage("LIVE_CERTIFICATION_APPROVAL_INCOMPLETE");
        assertThatThrownBy(() -> expired.requireOpen(NOW))
                .hasMessage("LIVE_CERTIFICATION_AUTHORIZATION_EXPIRED");
    }

    @Test
    void openAuthorizationWindowCannotExceedFourHours() {
        var exactWindow = authorization("OPEN", List.of(LiveCertificationAuthorization.FLASH_PROFILE),
                180, 3_600_000, "user", "2026-08-08T07:00:00Z",
                "2026-08-08T11:00:00Z", "60 synthetic flash cases");
        var oversizedWindow = authorization("OPEN", List.of(LiveCertificationAuthorization.FLASH_PROFILE),
                180, 3_600_000, "user", "2026-08-08T07:00:00Z",
                "2026-08-08T11:00:01Z", "60 synthetic flash cases");

        exactWindow.requireOpen(NOW);
        assertThatThrownBy(() -> oversizedWindow.requireOpen(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LIVE_CERTIFICATION_AUTHORIZATION_WINDOW_EXCEEDED");
    }

    @Test
    void everyAuthorizedProfileReceivesTheWholeCorpus() {
        var authorization = authorization("PROPOSED", List.of(
                        LiveCertificationAuthorization.FLASH_PROFILE,
                        LiveCertificationAuthorization.MAX_PROFILE
                ), 360, 54_000_000, null, null, null, null);
        var corpus = new LiveEvaluationCorpus();

        assertThat(authorization.assignmentCount()).isEqualTo(120);
        assertThat(authorization.assignments(corpus)).hasSize(120);
        assertThat(authorization.assignments(corpus).stream()
                .filter(item -> item.profileId().equals(LiveCertificationAuthorization.FLASH_PROFILE)))
                .hasSize(60);
        assertThat(authorization.assignments(corpus).stream()
                .filter(item -> item.profileId().equals(LiveCertificationAuthorization.MAX_PROFILE)))
                .hasSize(60);
    }

    @Test
    void pinnedPlusPromptVersionsUseSeparateWholeCorpusAndTenYuanCeilings() {
        for (var profileId : List.of(
                LiveCertificationAuthorization.PLUS_PROFILE,
                LiveCertificationAuthorization.PLUS_PROMPT_V2_PROFILE
        )) {
            var authorization = authorization(
                    "PROPOSED", List.of(profileId),
                    180, 10_000_000, null, null, null, null
            );

            assertThat(authorization.assignmentCount()).isEqualTo(60);
            assertThat(authorization.maximumProviderAttempts()).isEqualTo(180);
            assertThat(authorization.maximumCostMicrosCny()).isEqualTo(10_000_000);
            assertThat(authorization.assignments(new LiveEvaluationCorpus()))
                    .allMatch(item -> item.profileId().equals(profileId));
            assertThatThrownBy(() -> authorization(
                    "PROPOSED", List.of(profileId),
                    180, 10_000_001, null, null, null, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Certification authorization budget is invalid");
        }
    }

    @Test
    void groundedProfileExcludesTwentyDeterministicJsonCasesFromProviderAttemptCeiling() {
        var profileId = LiveCertificationAuthorization.PLUS_GROUNDED_PROFILE;
        var authorization = authorization(
                "PROPOSED", List.of(profileId),
                120, 10_000_000, null, null, null, null
        );

        assertThat(authorization.assignmentCount()).isEqualTo(60);
        assertThat(authorization.maximumProviderAttempts()).isEqualTo(120);
        assertThatThrownBy(() -> authorization(
                "PROPOSED", List.of(profileId),
                121, 10_000_000, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Certification authorization budget is invalid");
    }

    @Test
    void imageOnlyDiagnosticPlanIsNotCertificationAndSelectsExactlyTwentyGroundedCases() {
        var authorization = imageDiagnosticAuthorization(
                LiveCertificationAuthorization.PLUS_GROUNDED_PROFILE, 60, 2_000_000
        );
        var assignments = authorization.assignments(new LiveEvaluationCorpus());

        assertThat(authorization.evaluationPurpose()).isEqualTo("IMAGE_ONLY_DIAGNOSTIC");
        assertThat(authorization.certificationEligible()).isFalse();
        assertThat(authorization.assignmentCount()).isEqualTo(20);
        assertThat(assignments).hasSize(20)
                .allMatch(item -> item.profileId().equals(
                        LiveCertificationAuthorization.PLUS_GROUNDED_PROFILE
                ))
                .allMatch(item -> item.evaluationCase().mode() == InferenceMode.IMAGE_ONLY);

        assertThatThrownBy(() -> imageDiagnosticAuthorization(
                LiveCertificationAuthorization.PLUS_GROUNDED_PROFILE, 61, 2_000_000
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Certification authorization budget is invalid");
        assertThatThrownBy(() -> imageDiagnosticAuthorization(
                LiveCertificationAuthorization.PLUS_PROFILE, 60, 2_000_000
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Certification authorization profiles are invalid");
    }

    @Test
    void repositoryImageDiagnosticLedgerCannotDriftBeyondTheHardDiagnosticEnvelope() {
        var ledger = LiveCertificationAuthorizationLocator.resolve(
                DashScopeLiveCertificationTest.repositoryRoot(),
                "p5-image-only-diagnostic-20260809"
        );
        var authorization = LiveCertificationAuthorization.load(ledger, new ObjectMapper());

        assertThat(authorization.authorizationVersion())
                .isEqualTo(LiveCertificationAuthorization.IMAGE_DIAGNOSTIC_VERSION);
        assertThat(authorization.authorizationId()).isEqualTo("p5-image-only-diagnostic-20260809");
        assertThat(authorization.inputClassification())
                .isEqualTo(LiveCertificationAuthorization.INPUT_CLASSIFICATION);
        assertThat(authorization.profileIds())
                .containsExactly(LiveCertificationAuthorization.PLUS_GROUNDED_PROFILE);
        assertThat(authorization.maximumProviderAttempts()).isEqualTo(60);
        assertThat(authorization.maximumCostMicrosCny()).isEqualTo(2_000_000);
        assertThat(authorization.maximumCasesPerBatch()).isEqualTo(5);
        assertThat(authorization.assignmentCount()).isEqualTo(20);
        assertThat(authorization.certificationEligible()).isFalse();
    }

    @Test
    void authorizationLedgerRejectsDuplicateUnknownAndCoercedControlFields() throws Exception {
        var source = Files.readString(LiveCertificationAuthorizationLocator.resolve(
                DashScopeLiveCertificationTest.repositoryRoot(),
                "p5-image-only-diagnostic-20260809"
        ));
        var target = temporaryDirectory.resolve("authorization.json");

        Files.writeString(target, source.replaceFirst(
                "(\"status\"\\s*:\\s*\"PROPOSED\")", "$1,\"status\":\"OPEN\""
        ));
        assertInvalidLedger(target);

        Files.writeString(target, source.replaceFirst("\\{", "{\"unknownControl\":true,"));
        assertInvalidLedger(target);

        Files.writeString(target, source.replaceFirst(
                "(\"maximumProviderAttempts\"\\s*:\\s*)60", "$1\"60\""
        ));
        assertInvalidLedger(target);

        Files.writeString(target, source.replaceFirst(
                "(\"maximumCostMicrosCny\"\\s*:\\s*)2000000", "$1 2000000.5"
        ));
        assertInvalidLedger(target);
    }

    @Test
    void authorizationCannotExpandBeyondDesignedOrAbsoluteCaps() {
        assertThatThrownBy(() -> authorization(
                "PROPOSED", List.of(LiveCertificationAuthorization.FLASH_PROFILE),
                181, 3_600_000, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Certification authorization budget is invalid");
        assertThatThrownBy(() -> authorization(
                "PROPOSED", List.of(LiveCertificationAuthorization.FLASH_PROFILE),
                180, 3_600_001, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Certification authorization budget is invalid");
        assertThatThrownBy(() -> new LiveCertificationAuthorization(
                LiveCertificationAuthorization.VERSION,
                "p5-certification-oversized-batch",
                "PROPOSED",
                LiveCertificationAuthorization.INPUT_CLASSIFICATION,
                LiveEvaluationCorpus.VERSION,
                EVALUATION_IDENTITY,
                List.of(LiveCertificationAuthorization.FLASH_PROFILE),
                180,
                3_600_000,
                6,
                null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Certification authorization budget is invalid");
    }

    @Test
    void openAuthorizationRequiresAnExactImmutableEvaluationIdentity() {
        assertThatThrownBy(() -> new LiveCertificationAuthorization(
                LiveCertificationAuthorization.VERSION,
                "p5-certification-invalid-identity",
                "OPEN",
                LiveCertificationAuthorization.INPUT_CLASSIFICATION,
                LiveEvaluationCorpus.VERSION,
                LiveCertificationAuthorization.PENDING_EVALUATION_IDENTITY,
                List.of(LiveCertificationAuthorization.FLASH_PROFILE),
                180, 3_600_000, 5,
                "user", "2026-08-08T07:00:00Z", "2026-08-08T09:00:00Z",
                "60 synthetic flash cases"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Certification evaluation identity is invalid");

        var authorization = authorization(
                "OPEN", List.of(LiveCertificationAuthorization.FLASH_PROFILE),
                180, 3_600_000, "user", "2026-08-08T07:00:00Z",
                "2026-08-08T09:00:00Z", "60 synthetic flash cases"
        );
        assertThatThrownBy(() -> authorization.requireEvaluationIdentity(
                "renderweave-repository-tree-sha256/1:" + "b".repeat(64)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("LIVE_CERTIFICATION_EVALUATION_IDENTITY_MISMATCH");
    }

    private static LiveCertificationAuthorization authorization(
            String status,
            List<String> profileIds,
            int maximumAttempts,
            long maximumCost,
            String approvedBy,
            String approvedAt,
            String expiresAt,
            String approvalScope
    ) {
        return new LiveCertificationAuthorization(
                LiveCertificationAuthorization.VERSION,
                "p5-certification-test",
                status,
                LiveCertificationAuthorization.INPUT_CLASSIFICATION,
                LiveEvaluationCorpus.VERSION,
                EVALUATION_IDENTITY,
                profileIds,
                maximumAttempts,
                maximumCost,
                5,
                approvedBy,
                approvedAt,
                expiresAt,
                approvalScope
        );
    }

    private static LiveCertificationAuthorization imageDiagnosticAuthorization(
            String profileId,
            int maximumAttempts,
            long maximumCost
    ) {
        return new LiveCertificationAuthorization(
                LiveCertificationAuthorization.IMAGE_DIAGNOSTIC_VERSION,
                "p5-image-only-diagnostic-test",
                "PROPOSED",
                LiveCertificationAuthorization.INPUT_CLASSIFICATION,
                LiveEvaluationCorpus.VERSION,
                EVALUATION_IDENTITY,
                List.of(profileId),
                maximumAttempts,
                maximumCost,
                5,
                null, null, null, null
        );
    }

    private static void assertInvalidLedger(Path path) {
        assertThatThrownBy(() -> LiveCertificationAuthorization.load(path, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Certification authorization cannot be loaded");
    }
}
