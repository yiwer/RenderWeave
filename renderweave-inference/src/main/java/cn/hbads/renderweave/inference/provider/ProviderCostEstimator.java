package cn.hbads.renderweave.inference.provider;

import cn.hbads.renderweave.inference.profile.InferenceProfile;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class ProviderCostEstimator {
    private static final long TOKENS_PER_PRICE_UNIT = 1_000_000L;
    /** Covers chat/message framing and remains deliberately larger than current provider overhead. */
    private static final long TEXT_MESSAGE_OVERHEAD_TOKENS = 2_048L;
    /**
     * DashScope's documented default visual conversion is below 400 tokens per image. We do not
     * enable high-resolution mode; 1,024 is a conservative per-image billing bound.
     */
    private static final long IMAGE_INPUT_TOKEN_UPPER_BOUND = 1_024L;

    private ProviderCostEstimator() { }

    public static long estimateMicrosCny(InferenceProfile profile, ProviderUsage usage) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(usage, "usage");
        var pricingMultiplier = pricingMultiplier(profile, usage.inputTokens());
        return Math.addExact(
                price(usage.inputTokens(), Math.multiplyExact(
                        profile.inputMicrosCnyPerMillionTokens(), pricingMultiplier
                )),
                price(usage.outputTokens(), Math.multiplyExact(
                        profile.outputMicrosCnyPerMillionTokens(), pricingMultiplier
                ))
        );
    }

    /** Pre-call upper bound used for the irreversible external-cost gate. */
    public static long maximumRequestCostMicrosCny(ProviderInferenceRequest request) {
        Objects.requireNonNull(request, "request");
        var promptBytes = request.systemPrompt().getBytes(StandardCharsets.UTF_8).length;
        var taskBytes = request.taskJson().getBytes(StandardCharsets.UTF_8).length;
        var textTokens = Math.addExact(TEXT_MESSAGE_OVERHEAD_TOKENS, Math.addExact(promptBytes, taskBytes));
        var imageTokens = Math.multiplyExact((long) request.images().size(), IMAGE_INPUT_TOKEN_UPPER_BOUND);
        var maximumInputTokens = Math.addExact(textTokens, imageTokens);
        return estimateMicrosCny(
                request.profile(),
                new ProviderUsage(maximumInputTokens, request.profile().maximumOutputTokens())
        );
    }

    /**
     * qwen3.7-flash uses 1x/3x/6x input and output prices at the documented
     * 32K and 256K input-token boundaries. Other approved P5 models use the
     * snapshot rate throughout the request sizes that can pass their Profile cost gate.
     */
    private static long pricingMultiplier(InferenceProfile profile, long inputTokens) {
        if (!"qwen3.7-flash".equals(profile.model())) return 1L;
        if (inputTokens <= 32_000L) return 1L;
        if (inputTokens <= 256_000L) return 3L;
        return 6L;
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
