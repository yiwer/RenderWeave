package cn.hbads.renderweave.inference.run;

import cn.hbads.renderweave.inference.input.NormalizedArtifact;

import java.util.Objects;

public record InferenceRunInput(
        NormalizedArtifact.Kind kind,
        int ordinal,
        NormalizedArtifact artifact
) {
    public InferenceRunInput {
        Objects.requireNonNull(kind, "kind");
        if (ordinal < 0) throw new IllegalArgumentException("ordinal must not be negative");
        Objects.requireNonNull(artifact, "artifact");
        if (kind != artifact.kind()) throw new IllegalArgumentException("input and artifact kind must match");
    }
}
