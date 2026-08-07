package cn.hbads.renderweave.inference.input;

import java.util.Objects;

public record NormalizedArtifact(
        String artifactId,
        Kind kind,
        String locator,
        String mediaType,
        long byteLength,
        Integer width,
        Integer height
) {
    public NormalizedArtifact {
        if (artifactId == null || !artifactId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("artifactId must be a SHA-256 hex digest");
        }
        Objects.requireNonNull(kind, "kind");
        if (locator == null || locator.isBlank()) throw new IllegalArgumentException("locator is required");
        if (mediaType == null || mediaType.isBlank()) throw new IllegalArgumentException("mediaType is required");
        if (byteLength < 0) throw new IllegalArgumentException("byteLength must not be negative");
        if ((width == null) != (height == null)) throw new IllegalArgumentException("image dimensions must be paired");
    }

    public enum Kind { IMAGE, JSON_PROFILE }
}
