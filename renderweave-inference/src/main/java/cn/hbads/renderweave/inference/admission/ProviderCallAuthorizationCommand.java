package cn.hbads.renderweave.inference.admission;

import cn.hbads.renderweave.inference.input.InferenceMode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Everything required to atomically authorize one Provider call before any byte leaves. */
public record ProviderCallAuthorizationCommand(
        UUID runId,
        InferenceMode mode,
        int attemptOrdinal,
        String budgetKey,
        String profileId,
        String profileSha256,
        String endpoint,
        String inputFingerprint,
        long maximumRequestCostMicrosCny,
        Long runCostLimitMicrosCny,
        Instant now
) {
    public ProviderCallAuthorizationCommand {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(budgetKey, "budgetKey");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(profileSha256, "profileSha256");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(inputFingerprint, "inputFingerprint");
        Objects.requireNonNull(now, "now");
        if (attemptOrdinal < 0 || attemptOrdinal > 11) {
            throw new IllegalArgumentException("attemptOrdinal is out of range");
        }
        if (maximumRequestCostMicrosCny < 1) {
            throw new IllegalArgumentException("maximumRequestCostMicrosCny must be positive");
        }
        if (runCostLimitMicrosCny != null && runCostLimitMicrosCny < 1) {
            throw new IllegalArgumentException("runCostLimitMicrosCny must be positive when present");
        }
        if (!endpoint.startsWith("https://")) {
            throw new IllegalArgumentException("Provider endpoint must be exact HTTPS");
        }
        if (!profileSha256.matches("[0-9a-f]{64}") || !inputFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Command digests must be lowercase SHA-256 hex");
        }
    }
}
