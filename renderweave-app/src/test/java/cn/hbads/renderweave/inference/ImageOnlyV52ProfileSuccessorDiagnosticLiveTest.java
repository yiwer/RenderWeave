package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.certification.AuthorizationStatus;
import cn.hbads.renderweave.inference.certification.ImageOnlyCertificationAuthorizationJsonCodec;
import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Historical v52 authority is immutable and CLOSED; its paid harness cannot be replayed. */
@EnabledIfEnvironmentVariable(
        named = "RENDERWEAVE_RUN_V52_PROFILE_SUCCESSOR_DIAGNOSTIC", matches = "true")
class ImageOnlyV52ProfileSuccessorDiagnosticLiveTest {
    // Historical exact contract retained for independent replay only:
    // 20260818-iopa-v52-diagnostic-981d7262
    // 981d7262-d802-45bb-96ce-d34b4468f9f9
    // renderweave-image-only-fresh-normalization/1.0:e0e505c515ff3c7c7bac57e0ddc19e714721e301fd2216830bc6ac82f98cae35
    // PROFILE_SUCCESSOR_AUTHORIZATION_ALREADY_EXECUTED; automatic rerun is forbidden.
    @Test
    void closedHistoricalAuthorizationCannotBeReused() throws Exception {
        var path = repositoryRoot().resolve("plans/live-canary-authorizations")
                .resolve("20260818-image-only-v52-diagnostic-981d7262.json");
        var authorization = new ImageOnlyCertificationAuthorizationJsonCodec()
                .read(Files.readAllBytes(path));

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.CLOSED);
        assertThat(authorization.profileId())
                .isEqualTo(ProfileRunBudgetPolicy.IMAGE_ONLY_V52_PROFILE_ID);
        assertThat(authorization.normalizationIdentity()).isEqualTo(
                "renderweave-image-only-fresh-normalization/1.0:"
                        + "e0e505c515ff3c7c7bac57e0ddc19e714721e301fd2216830bc6ac82f98cae35");
        assertThat(authorization.closureReason()).isEqualTo(
                "PROFILE_SUCCESSOR_DIAGNOSTIC_REVIEW_REQUIRED_OWNER_REVIEW_PENDING");
    }

    private static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        return Files.isDirectory(current.resolve("plans")) ? current : current.getParent();
    }
}
