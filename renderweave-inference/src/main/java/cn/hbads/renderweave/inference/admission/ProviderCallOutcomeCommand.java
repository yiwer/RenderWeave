package cn.hbads.renderweave.inference.admission;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Settlement fact for one dispatched Provider call; recorded with the audit chain atomically. */
public record ProviderCallOutcomeCommand(
        ProviderCallPermit permit,
        Outcome outcome,
        String failureCode,
        Long usageInputTokens,
        Long usageOutputTokens,
        Long actualCostMicrosCny,
        Instant now
) {
    public ProviderCallOutcomeCommand {
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(now, "now");
        if (outcome == Outcome.DISPATCH_FAILED) {
            if (failureCode == null || !failureCode.matches("[A-Z][A-Z0-9_]{2,95}")) {
                throw new IllegalArgumentException("Failed dispatch requires a closed failure code");
            }
        } else if (failureCode != null) {
            throw new IllegalArgumentException("Successful dispatch carries no failure code");
        }
        if (outcome == Outcome.DISPATCH_SUCCEEDED) {
            Objects.requireNonNull(usageInputTokens, "usageInputTokens");
            Objects.requireNonNull(usageOutputTokens, "usageOutputTokens");
            Objects.requireNonNull(actualCostMicrosCny, "actualCostMicrosCny");
            if (usageInputTokens < 0 || usageOutputTokens < 0 || actualCostMicrosCny < 0) {
                throw new IllegalArgumentException("Usage and cost must be non-negative");
            }
        }
    }

    public enum Outcome {
        DISPATCH_SUCCEEDED,
        DISPATCH_FAILED
    }
}
