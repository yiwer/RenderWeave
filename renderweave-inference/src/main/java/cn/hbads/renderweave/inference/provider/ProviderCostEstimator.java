package cn.hbads.renderweave.inference.provider;

import cn.hbads.renderweave.inference.profile.InferenceProfile;

import java.util.Objects;

public final class ProviderCostEstimator {
    private static final long TOKENS_PER_PRICE_UNIT = 1_000_000L;

    private ProviderCostEstimator() { }

    public static long estimateMicrosCny(InferenceProfile profile, ProviderUsage usage) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(usage, "usage");
        return Math.addExact(
                price(usage.inputTokens(), profile.inputMicrosCnyPerMillionTokens()),
                price(usage.outputTokens(), profile.outputMicrosCnyPerMillionTokens())
        );
    }

    private static long price(long tokens, long microsPerMillionTokens) {
        if (tokens == 0 || microsPerMillionTokens == 0) return 0;
        var wholeMillions = tokens / TOKENS_PER_PRICE_UNIT;
        var remainder = tokens % TOKENS_PER_PRICE_UNIT;
        var whole = Math.multiplyExact(wholeMillions, microsPerMillionTokens);
        var fractionalNumerator = Math.multiplyExact(remainder, microsPerMillionTokens);
        var fractional = Math.floorDiv(
                Math.addExact(fractionalNumerator, TOKENS_PER_PRICE_UNIT - 1),
                TOKENS_PER_PRICE_UNIT
        );
        return Math.addExact(whole, fractional);
    }
}
