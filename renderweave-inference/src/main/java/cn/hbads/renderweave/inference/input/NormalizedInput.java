package cn.hbads.renderweave.inference.input;

import java.util.List;
import java.util.Objects;

public record NormalizedInput(
        InferenceMode mode,
        String profileId,
        String sourceReference,
        String inputFingerprint,
        List<NormalizedArtifact> artifacts,
        List<NormalizedInputReference> references,
        List<String> newlyCreatedLocators
) {
    public NormalizedInput {
        Objects.requireNonNull(mode, "mode");
        if (profileId == null || profileId.isBlank()) throw new IllegalArgumentException("profileId is required");
        if (sourceReference == null || sourceReference.isBlank()) {
            throw new IllegalArgumentException("sourceReference is required");
        }
        if (inputFingerprint == null || !inputFingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("inputFingerprint must be a SHA-256 hex digest");
        }
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        newlyCreatedLocators = List.copyOf(Objects.requireNonNull(newlyCreatedLocators, "newlyCreatedLocators"));

        var artifactIds = artifacts.stream().map(NormalizedArtifact::artifactId).collect(java.util.stream.Collectors.toSet());
        if (artifactIds.size() != artifacts.size()) throw new IllegalArgumentException("artifacts must be unique");
        if (references.stream().anyMatch(reference -> !artifactIds.contains(reference.artifactId()))) {
            throw new IllegalArgumentException("every input reference must resolve to an artifact");
        }
        var referencedArtifactIds = references.stream()
                .map(NormalizedInputReference::artifactId)
                .collect(java.util.stream.Collectors.toSet());
        if (!referencedArtifactIds.equals(artifactIds)) {
            throw new IllegalArgumentException("every artifact must have at least one input reference");
        }
        var referenceKeys = references.stream()
                .map(reference -> reference.kind() + ":" + reference.ordinal())
                .collect(java.util.stream.Collectors.toSet());
        if (referenceKeys.size() != references.size()) {
            throw new IllegalArgumentException("input reference ordinals must be unique per kind");
        }
    }
}
