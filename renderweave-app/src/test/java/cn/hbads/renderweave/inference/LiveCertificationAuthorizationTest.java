package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.LiveEvaluationCorpus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveCertificationAuthorizationTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final String EVALUATION_IDENTITY =
            "renderweave-repository-tree-sha256/1:" + "a".repeat(64);

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
}
