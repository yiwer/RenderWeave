package cn.hbads.renderweave.inference.run;

import cn.hbads.renderweave.inference.input.NormalizedInput;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record NewInferenceRun(
        UUID runId,
        String idempotencyKey,
        String requestFingerprint,
        NormalizedInput normalizedInput,
        String profileSnapshotJson,
        Long costLimitMicrosCny,
        Optional<UUID> retryOfRunId,
        Instant createdAt
) {
    public NewInferenceRun {
        Objects.requireNonNull(runId, "runId");
        idempotencyKey = validateIdempotencyKey(idempotencyKey);
        if (requestFingerprint == null || !requestFingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("requestFingerprint must be a SHA-256 hex digest");
        }
        Objects.requireNonNull(normalizedInput, "normalizedInput");
        profileSnapshotJson = requireBoundedText(profileSnapshotJson, "profileSnapshotJson", 1_048_576);
        if (costLimitMicrosCny != null && costLimitMicrosCny < 1) {
            throw new IllegalArgumentException("costLimitMicrosCny must be positive when present");
        }
        retryOfRunId = Objects.requireNonNull(retryOfRunId, "retryOfRunId");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static NewInferenceRun initial(
            UUID runId,
            String idempotencyKey,
            NormalizedInput normalizedInput,
            String profileSnapshotJson,
            Instant createdAt
    ) {
        return initial(runId, idempotencyKey, normalizedInput, profileSnapshotJson, null, createdAt);
    }

    public static NewInferenceRun initial(
            UUID runId,
            String idempotencyKey,
            NormalizedInput normalizedInput,
            String profileSnapshotJson,
            Long costLimitMicrosCny,
            Instant createdAt
    ) {
        return new NewInferenceRun(
                runId, idempotencyKey, requestFingerprint(normalizedInput.inputFingerprint(), costLimitMicrosCny),
                normalizedInput, profileSnapshotJson, costLimitMicrosCny, Optional.empty(), createdAt
        );
    }

    private static String requestFingerprint(String inputFingerprint, Long costLimitMicrosCny) {
        if (costLimitMicrosCny == null) return inputFingerprint;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    ("renderweave-live-request/1\u0000" + inputFingerprint + "\u0000" + costLimitMicrosCny)
                            .getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }

    public static String validateIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey must contain 1..128 characters");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("idempotencyKey must not contain control characters");
        }
        return value;
    }

    private static String requireBoundedText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain 1.." + maximumLength + " characters");
        }
        return value;
    }
}
