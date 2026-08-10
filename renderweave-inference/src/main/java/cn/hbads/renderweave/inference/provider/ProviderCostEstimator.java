package cn.hbads.renderweave.inference.provider;

import cn.hbads.renderweave.inference.profile.InferenceProfile;
import cn.hbads.renderweave.inference.input.ImageNormalizer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class ProviderCostEstimator {
    private static final long TOKENS_PER_PRICE_UNIT = 1_000_000L;
    /** Covers chat/message framing and remains deliberately larger than current provider overhead. */
    private static final long TEXT_MESSAGE_OVERHEAD_TOKENS = 2_048L;
    private static final long VISUAL_PATCH_PIXELS = 32L * 32L;
    private static final long VISUAL_TOKEN_OVERHEAD = 2L;
    /**
     * DashScope documents visual tokens as height * width / (32 * 32) + 2. ProviderImage is
     * reached only after ImageNormalizer, so reserving the normalized 4,096-square maximum for
     * every image is deliberately conservative and closes cost before the irreversible call.
     */
    private static final long IMAGE_INPUT_TOKEN_UPPER_BOUND = Math.addExact(
            Math.ceilDiv(
                    Math.multiplyExact(
                            (long) ImageNormalizer.MAX_LONG_EDGE,
                            ImageNormalizer.MAX_LONG_EDGE
                    ),
                    VISUAL_PATCH_PIXELS
            ),
            VISUAL_TOKEN_OVERHEAD
    );

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
        return estimateMicrosCny(
                Objects.requireNonNull(request, "request").profile(),
                maximumRequestUsage(request)
        );
    }

    /** Pre-call total token upper bound used by cross-ledger Goal authorization. */
    public static long maximumRequestTokens(ProviderInferenceRequest request) {
        var usage = maximumRequestUsage(Objects.requireNonNull(request, "request"));
        return Math.addExact(usage.inputTokens(), usage.outputTokens());
    }

    private static ProviderUsage maximumRequestUsage(ProviderInferenceRequest request) {
        Objects.requireNonNull(request, "request");
        var promptBytes = request.systemPrompt().getBytes(StandardCharsets.UTF_8).length;
        var taskBytes = request.taskJson().getBytes(StandardCharsets.UTF_8).length;
        var textTokens = Math.addExact(TEXT_MESSAGE_OVERHEAD_TOKENS, Math.addExact(promptBytes, taskBytes));
        var imageTokens = Math.multiplyExact((long) request.images().size(), IMAGE_INPUT_TOKEN_UPPER_BOUND);
        var maximumInputTokens = Math.addExact(textTokens, imageTokens);
        return new ProviderUsage(maximumInputTokens, request.profile().maximumOutputTokens());
    }

    /**
     * qwen3.7-flash uses 1x/3x/6x input and output prices at the documented
     * 32K and 256K input-token boundaries. qwen3.7-plus uses 1x/3x at 256K.
     * Other approved P5 models use the snapshot rate throughout request sizes
     * that can pass their Profile cost gate.
     */
    private static long pricingMultiplier(InferenceProfile profile, long inputTokens) {
        if ("qwen3.7-flash".equals(profile.model())) {
            if (inputTokens <= 32_000L) return 1L;
            if (inputTokens <= 256_000L) return 3L;
            return 6L;
        }
        if ("qwen3.7-plus-2026-05-26".equals(profile.model())
                || "qwen3.7-plus".equals(profile.model())) {
            return inputTokens <= 256_000L ? 1L : 3L;
        }
        return 1L;
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
