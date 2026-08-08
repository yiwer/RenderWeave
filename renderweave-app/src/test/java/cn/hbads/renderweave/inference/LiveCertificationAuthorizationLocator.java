package cn.hbads.renderweave.inference;

import java.nio.file.Path;
import java.util.Objects;

/** Selects one repository-versioned ledger without accepting arbitrary filesystem paths. */
final class LiveCertificationAuthorizationLocator {
    static final String SELECTOR_ENVIRONMENT_VARIABLE =
            "RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION";
    static final String DEFAULT_LEDGER = "p5-certification-20260808";

    private LiveCertificationAuthorizationLocator() { }

    static Path resolve(Path repositoryRoot) {
        return resolve(repositoryRoot, System.getenv(SELECTOR_ENVIRONMENT_VARIABLE));
    }

    static Path resolve(Path repositoryRoot, String selector) {
        var root = Objects.requireNonNull(repositoryRoot, "repositoryRoot")
                .toAbsolutePath().normalize();
        var ledger = selector == null || selector.isEmpty() ? DEFAULT_LEDGER : selector;
        if (!ledger.matches("[a-z0-9][a-z0-9-]{0,95}")) {
            throw new IllegalArgumentException("LIVE_CERTIFICATION_AUTHORIZATION_SELECTOR_INVALID");
        }
        var directory = root.resolve("plans").resolve("live-certification-authorizations").normalize();
        var selected = directory.resolve(ledger + ".json").normalize();
        if (!selected.startsWith(directory)) {
            throw new IllegalArgumentException("LIVE_CERTIFICATION_AUTHORIZATION_SELECTOR_INVALID");
        }
        return selected;
    }
}
