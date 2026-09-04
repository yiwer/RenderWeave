package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.ImageOnlyAdmissionPolicyStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Test-only helpers that open the dual switches explicitly; production wiring stays closed. */
final class DualSwitchTestFixtures {
    static final String OPS_IDENTITY = "test-ops-mtls-identity";

    private DualSwitchTestFixtures() { }

    static Path writeEgressPermit(Path directory) {
        try {
            Files.createDirectories(directory);
            var permit = directory.resolve("provider-egress-permit.txt");
            Files.write(permit, List.of(
                    FileSystemProviderEgressPermit.EXPECTED_HEADER,
                    "enabled=true",
                    "identity=test-egress-permit-1"
            ), StandardCharsets.UTF_8);
            return permit;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    static void enableAdmissionPolicy(ImageOnlyAdmissionPolicyStore policyStore) {
        if (!policyStore.current().enabled()) {
            policyStore.append(true, OPS_IDENTITY, "OPS_ENABLED", Instant.now());
        }
    }

    static void disableAdmissionPolicy(ImageOnlyAdmissionPolicyStore policyStore) {
        if (policyStore.current().enabled()) {
            policyStore.append(false, OPS_IDENTITY, "OPS_DISABLED", Instant.now());
        }
    }
}
