package cn.hbads.renderweave.inference.run;

import java.util.Objects;

public record InferenceArtifactDeletion(String artifactId, String locator) {
    public InferenceArtifactDeletion {
        if (artifactId == null || !artifactId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("artifactId must be a SHA-256 hex digest");
        }
        if (locator == null || locator.isBlank()) throw new IllegalArgumentException("locator is required");
    }
}
