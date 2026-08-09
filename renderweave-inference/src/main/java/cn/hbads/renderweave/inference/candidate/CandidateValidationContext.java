package cn.hbads.renderweave.inference.candidate;

import cn.hbads.renderweave.inference.profile.JsonStructuralProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CandidateValidationContext(
        Set<String> imageArtifactIds,
        int jsonSampleCount,
        Map<String, Set<CandidateEvidence>> jsonEvidenceByNodePointer,
        int lowConfidenceThresholdBps,
        CandidateValidationOrigin origin
) {
    public CandidateValidationContext {
        imageArtifactIds = Set.copyOf(Objects.requireNonNull(imageArtifactIds, "imageArtifactIds"));
        if (jsonSampleCount < 0 || jsonSampleCount > 20) {
            throw new IllegalArgumentException("jsonSampleCount must be 0..20");
        }
        if (lowConfidenceThresholdBps < 0 || lowConfidenceThresholdBps > 10_000) {
            throw new IllegalArgumentException("lowConfidenceThresholdBps must be 0..10000");
        }
        Objects.requireNonNull(jsonEvidenceByNodePointer, "jsonEvidenceByNodePointer");
        var catalog = new LinkedHashMap<String, Set<CandidateEvidence>>();
        jsonEvidenceByNodePointer.forEach((nodePointer, locations) -> {
            Objects.requireNonNull(nodePointer, "jsonEvidence nodePointer");
            var safeLocations = Set.copyOf(Objects.requireNonNull(locations, "jsonEvidence locations"));
            for (var location : safeLocations) {
                if (location.kind() != CandidateEvidenceKind.JSON
                        || location.artifactId() != null || location.boundingBox() != null
                        || location.sampleIndex() == null || location.jsonPointer() == null
                        || location.sampleIndex() < 0 || location.sampleIndex() >= jsonSampleCount) {
                    throw new IllegalArgumentException("jsonEvidence catalog is invalid");
                }
            }
            catalog.put(nodePointer, safeLocations);
        });
        jsonEvidenceByNodePointer = Map.copyOf(catalog);
        Objects.requireNonNull(origin, "origin");
    }

    public static CandidateValidationContext liveProviderOutput(
            Set<String> imageArtifactIds,
            JsonStructuralProfile profile,
            int lowConfidenceThresholdBps
    ) {
        return fromProfile(
                imageArtifactIds, profile, lowConfidenceThresholdBps,
                CandidateValidationOrigin.LIVE_PROVIDER_OUTPUT
        );
    }

    public static CandidateValidationContext trustedReplayOutput(
            Set<String> imageArtifactIds,
            JsonStructuralProfile profile,
            int lowConfidenceThresholdBps
    ) {
        return fromProfile(
                imageArtifactIds, profile, lowConfidenceThresholdBps,
                CandidateValidationOrigin.TRUSTED_REPLAY_OUTPUT
        );
    }

    public static CandidateValidationContext userReview(
            Set<String> imageArtifactIds,
            JsonStructuralProfile profile,
            int lowConfidenceThresholdBps
    ) {
        return fromProfile(
                imageArtifactIds, profile, lowConfidenceThresholdBps,
                CandidateValidationOrigin.USER_REVIEW
        );
    }

    boolean jsonEvidenceKnown(CandidateEvidence evidence) {
        return jsonEvidenceByNodePointer.values().stream().anyMatch(locations -> locations.contains(evidence));
    }

    boolean jsonEvidenceMatches(String nodePointer, CandidateEvidence evidence) {
        return jsonEvidenceByNodePointer.getOrDefault(nodePointer, Set.of()).contains(evidence);
    }

    private static CandidateValidationContext fromProfile(
            Set<String> imageArtifactIds,
            JsonStructuralProfile profile,
            int lowConfidenceThresholdBps,
            CandidateValidationOrigin origin
    ) {
        if (profile == null) {
            return new CandidateValidationContext(
                    imageArtifactIds, 0, Map.of(), lowConfidenceThresholdBps, origin
            );
        }
        var catalog = new LinkedHashMap<String, Set<CandidateEvidence>>();
        for (var node : profile.nodes()) {
            var locations = node.evidence().stream()
                    .map(location -> CandidateEvidence.json(
                            location.sampleIndex(), location.jsonPointer()
                    ))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            catalog.put(node.pointer(), Set.copyOf(locations));
        }
        return new CandidateValidationContext(
                imageArtifactIds, profile.sampleCount(), catalog, lowConfidenceThresholdBps, origin
        );
    }
}
