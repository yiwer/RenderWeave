package cn.hbads.renderweave.inference.candidate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InferenceCandidateSnapshot(
        UUID runId,
        long revision,
        String contractVersion,
        String originalJson,
        String currentJson,
        String validationProblemsJson,
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
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
