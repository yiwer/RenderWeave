package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.certification.AuthorizationStatus;
import cn.hbads.renderweave.inference.certification.ImageOnlyCertificationAuthorizationJsonCodec;
import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Historical v51 authority is immutable and CLOSED; its paid harness cannot be replayed. */
@EnabledIfEnvironmentVariable(
        named = "RENDERWEAVE_RUN_V51_PROFILE_SUCCESSOR_DIAGNOSTIC", matches = "true")
class ImageOnlyProfileSuccessorDiagnosticLiveTest {
    // Historical exact contract retained for independent replay only:
    // 20260818-iopa-v51-diagnostic-7d929b74
    // 7d929b74-47ca-40a7-bfd5-061e070c2bd2
    // renderweave-image-only-fresh-normalization/1.0:632c601ccdcbd561fcb9502777a888712a564a81352f9f19b163b4a0e9a6b4cc
    // PROFILE_SUCCESSOR_AUTHORIZATION_ALREADY_EXECUTED; automatic rerun is forbidden.
    @Test
    void closedHistoricalAuthorizationCannotBeReused() throws Exception {
        var path = repositoryRoot().resolve("plans/live-canary-authorizations")
                .resolve("20260818-image-only-v51-diagnostic-7d929b74.json");
        var authorization = new ImageOnlyCertificationAuthorizationJsonCodec()
                .read(Files.readAllBytes(path));

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.CLOSED);
        assertThat(authorization.profileId())
                .isEqualTo(ProfileRunBudgetPolicy.IMAGE_ONLY_V51_PROFILE_ID);
        assertThat(authorization.normalizationIdentity()).isEqualTo(
                "renderweave-image-only-fresh-normalization/1.0:"
                        + "632c601ccdcbd561fcb9502777a888712a564a81352f9f19b163b4a0e9a6b4cc");
        assertThat(authorization.closureReason())
                .isEqualTo("PROFILE_SUCCESSOR_DIAGNOSTIC_FAILED_"
                        + "VISUAL_GROUNDING_PARENT_CONTAINMENT_CLASSIFIED");
    }

    private static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        return Files.isDirectory(current.resolve("plans")) ? current : current.getParent();
    }
}
