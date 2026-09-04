package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.certification.AuthorizationStatus;
import cn.hbads.renderweave.inference.certification.ImageOnlyCertificationAuthorizationJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Historical v48 authority is immutable and CLOSED; its paid harness cannot be replayed. */
@EnabledIfEnvironmentVariable(
        named = "RENDERWEAVE_RUN_V48_PROFILE_SUCCESSOR_DIAGNOSTIC", matches = "true")
class ImageOnlyV48ProfileSuccessorDiagnosticLiveTest {
    @Test
    void closedHistoricalAuthorizationCannotBeReused() throws Exception {
        var path = repositoryRoot().resolve("plans/live-canary-authorizations")
                .resolve("20260818-image-only-v48-diagnostic-4e1f41b7.json");
        var authorization = new ImageOnlyCertificationAuthorizationJsonCodec()
                .read(Files.readAllBytes(path));

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.CLOSED);
        assertThat(authorization.closureReason())
                .isEqualTo("PROFILE_SUCCESSOR_DIAGNOSTIC_FAILED_VISUAL_GROUNDING_REGION_INVALID");
    }

    private static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        return Files.isDirectory(current.resolve("plans")) ? current : current.getParent();
    }
}
