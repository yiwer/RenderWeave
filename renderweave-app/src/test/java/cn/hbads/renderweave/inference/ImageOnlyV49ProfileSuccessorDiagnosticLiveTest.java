package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.certification.AuthorizationStatus;
import cn.hbads.renderweave.inference.certification.ImageOnlyCertificationAuthorizationJsonCodec;
import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Historical v49 authority is immutable and CLOSED; its paid harness cannot be replayed. */
@EnabledIfEnvironmentVariable(
        named = "RENDERWEAVE_RUN_V49_PROFILE_SUCCESSOR_DIAGNOSTIC", matches = "true")
class ImageOnlyV49ProfileSuccessorDiagnosticLiveTest {
    // Historical exact contract retained for independent replay only:
    // 20260818-iopa-v49-diagnostic-432fdfeb
    // 432fdfeb-c5ab-4cff-92f4-e066a0d98c8c
    // renderweave-image-only-fresh-normalization/1.0:3096deba42aeab03be175074e6717ccf6898d4a628950d19eaa6891674d62375
    // PROFILE_SUCCESSOR_AUTHORIZATION_ALREADY_EXECUTED; automatic rerun is forbidden.
    @Test
    void closedHistoricalAuthorizationCannotBeReused() throws Exception {
        var path = repositoryRoot().resolve("plans/live-canary-authorizations")
                .resolve("20260818-image-only-v49-diagnostic-432fdfeb.json");
        var authorization = new ImageOnlyCertificationAuthorizationJsonCodec()
                .read(Files.readAllBytes(path));

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.CLOSED);
        assertThat(authorization.profileId())
                .isEqualTo(ProfileRunBudgetPolicy.IMAGE_ONLY_V49_PROFILE_ID);
        assertThat(authorization.closureReason())
                .isEqualTo("PROFILE_SUCCESSOR_DIAGNOSTIC_FAILED_"
                        + "VISUAL_GROUNDING_REGION_FIELDS_INVALID");
    }

    private static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        return Files.isDirectory(current.resolve("plans")) ? current : current.getParent();
    }
}
