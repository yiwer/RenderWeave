package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.certification.AuthorizationStatus;
import cn.hbads.renderweave.inference.certification.ImageOnlyCertificationAuthorizationJsonCodec;
import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Historical v50 authority is immutable and CLOSED; its paid harness cannot be replayed. */
@EnabledIfEnvironmentVariable(
        named = "RENDERWEAVE_RUN_V50_PROFILE_SUCCESSOR_DIAGNOSTIC", matches = "true")
class ImageOnlyV50ProfileSuccessorDiagnosticLiveTest {
    // Historical exact contract retained for independent replay only:
    // 20260818-iopa-v50-diagnostic-82f1d86b
    // 82f1d86b-065b-4357-924e-19945daf1077
    // renderweave-image-only-fresh-normalization/1.0:146c27620edad71fd40618772c3c1fc8613684d83b91bf20edc5d944b7a4b8b4
    // PROFILE_SUCCESSOR_AUTHORIZATION_ALREADY_EXECUTED; automatic rerun is forbidden.
    @Test
    void closedHistoricalAuthorizationCannotBeReused() throws Exception {
        var path = repositoryRoot().resolve("plans/live-canary-authorizations")
                .resolve("20260818-image-only-v50-diagnostic-82f1d86b.json");
        var authorization = new ImageOnlyCertificationAuthorizationJsonCodec()
                .read(Files.readAllBytes(path));

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.CLOSED);
        assertThat(authorization.profileId())
                .isEqualTo(ProfileRunBudgetPolicy.IMAGE_ONLY_V50_PROFILE_ID);
        assertThat(authorization.closureReason())
                .isEqualTo("PROFILE_SUCCESSOR_DIAGNOSTIC_FAILED_"
                        + "VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID");
    }

    private static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        return Files.isDirectory(current.resolve("plans")) ? current : current.getParent();
    }
}
