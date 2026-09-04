package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.ProviderEgressPermit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Read-only adapter for the orchestrator/firewall egress authority. The permit artifact is
 * mounted from outside the application; any missing, unreadable or malformed state fails
 * closed. Credential existence never participates in this decision.
 */
final class FileSystemProviderEgressPermit implements ProviderEgressPermit {
    static final String EXPECTED_HEADER = "renderweave-provider-egress-permit/1.0";

    private final Path permitFile;

    private FileSystemProviderEgressPermit(Path permitFile) {
        this.permitFile = permitFile;
    }

    static ProviderEgressPermit fromConfiguration(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return ProviderEgressPermit.disabled();
        }
        return new FileSystemProviderEgressPermit(Path.of(configuredPath));
    }

    @Override
    public Snapshot snapshot() {
        final List<String> lines;
        try {
            lines = Files.readAllLines(permitFile, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException unavailable) {
            return Snapshot.DISABLED;
        }
        if (lines.size() != 3
                || !EXPECTED_HEADER.equals(lines.get(0).trim())
                || !"enabled=true".equals(lines.get(1).trim())) {
            return Snapshot.DISABLED;
        }
        var identityLine = lines.get(2).trim();
        if (!identityLine.startsWith("identity=")) {
            return Snapshot.DISABLED;
        }
        var identity = identityLine.substring("identity=".length());
        if (!identity.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,191}")) {
            return Snapshot.DISABLED;
        }
        return new Snapshot(true, identity);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FileSystemProviderEgressPermit permit
                && Objects.equals(permitFile, permit.permitFile);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(permitFile);
    }
}
