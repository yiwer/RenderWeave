package cn.hbads.renderweave.inference.candidate;

import java.time.Instant;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

public record InferenceCandidateSnapshot(
        UUID runId,
        long revision,
        String contractVersion,
        String originalJson,
        String currentJson,
        String validationProblemsJson,
        Optional<String> finalJson,
        Optional<Instant> appliedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public InferenceCandidateSnapshot {
        Objects.requireNonNull(runId, "runId");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        contractVersion = requireText(contractVersion, "contractVersion");
        originalJson = requireText(originalJson, "originalJson");
        currentJson = requireText(currentJson, "currentJson");
        validationProblemsJson = requireText(validationProblemsJson, "validationProblemsJson");
        finalJson = finalJson == null ? Optional.empty() : finalJson;
        appliedAt = appliedAt == null ? Optional.empty() : appliedAt;
        if (finalJson.isPresent() != appliedAt.isPresent()) {
            throw new IllegalArgumentException("finalJson and appliedAt must be present together");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
