package cn.hbads.renderweave.inference.input;

import java.util.Objects;

/** Preserves input occurrence order while allowing content-addressed artifacts to be deduplicated. */
public record NormalizedInputReference(
        NormalizedArtifact.Kind kind,
        int ordinal,
        String artifactId
) {
    public NormalizedInputReference {
        Objects.requireNonNull(kind, "kind");
        if (ordinal < 0) throw new IllegalArgumentException("ordinal must not be negative");
        if (artifactId == null || !artifactId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("artifactId must be a SHA-256 hex digest");
        }
    }
}
