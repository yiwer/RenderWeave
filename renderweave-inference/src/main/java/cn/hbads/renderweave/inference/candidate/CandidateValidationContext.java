package cn.hbads.renderweave.inference.candidate;

import java.util.Objects;
import java.util.Set;

public record CandidateValidationContext(
        Set<String> imageArtifactIds,
        int jsonSampleCount,
        int lowConfidenceThresholdBps
) {
    public CandidateValidationContext {
        imageArtifactIds = Set.copyOf(Objects.requireNonNull(imageArtifactIds, "imageArtifactIds"));
        if (jsonSampleCount < 0 || jsonSampleCount > 20) {
            throw new IllegalArgumentException("jsonSampleCount must be 0..20");
        }
        if (lowConfidenceThresholdBps < 0 || lowConfidenceThresholdBps > 10_000) {
            throw new IllegalArgumentException("lowConfidenceThresholdBps must be 0..10000");
        }
    }
}
