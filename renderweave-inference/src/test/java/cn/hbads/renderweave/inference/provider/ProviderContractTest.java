package cn.hbads.renderweave.inference.provider;

import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderContractTest {
    private final InferenceProfileRegistry profiles = new InferenceProfileRegistry();

    @Test
    void requestCarriesOnlyBoundedPromptJsonAndNormalizedMediaBytes() {
        var bytes = new byte[] {1, 2, 3};
        var image = new ProviderImage("a".repeat(64), "image/png", bytes);
        var request = new ProviderInferenceRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                0,
                InferenceStage.STRUCTURE,
                profiles.require("dashscope-qwen37-flash-v1").profile(),
                "Return JSON only.",
                "{\"mode\":\"IMAGE_ONLY\"}",
                List.of(image)
        );

        bytes[0] = 9;
        assertEquals("qwen3.7-flash", request.profile().model());
        assertArrayEquals(new byte[] {1, 2, 3}, request.images().getFirst().bytes());
        assertThrows(UnsupportedOperationException.class, () -> request.images().add(image));
        assertThrows(IllegalArgumentException.class, () -> new ProviderImage(
                "a".repeat(64), "image/svg+xml", new byte[] {1}
        ));
        assertThrows(IllegalArgumentException.class, () -> new ProviderInferenceRequest(
                request.runId(), 0, InferenceStage.STRUCTURE,
                profiles.require("replay-v1").profile(), request.systemPrompt(), request.taskJson(), List.of()
        ));
    }

    @Test
    void pricingUsesIntegerMicrosAndRoundsEachTokenClassUp() {
        var flash = profiles.require("dashscope-qwen37-flash-v1").profile();
        var max = profiles.require("dashscope-qwen38-max-v1").profile();

        assertEquals(600L, ProviderCostEstimator.estimateMicrosCny(flash, new ProviderUsage(1_000, 500)));
        assertEquals(30_000L, ProviderCostEstimator.estimateMicrosCny(max, new ProviderUsage(1_000, 500)));
        assertEquals(0L, ProviderCostEstimator.estimateMicrosCny(max, new ProviderUsage(0, 0)));
    }

    @Test
    void flashCostUsesTheOfficialInputLengthPricingTiers() {
        var flash = profiles.require("dashscope-qwen37-flash-v1").profile();

        assertEquals(6_480L,
                ProviderCostEstimator.estimateMicrosCny(flash, new ProviderUsage(32_000, 100)));
        assertEquals(19_441L,
                ProviderCostEstimator.estimateMicrosCny(flash, new ProviderUsage(32_001, 100)));
        assertEquals(307_682L,
                ProviderCostEstimator.estimateMicrosCny(flash, new ProviderUsage(256_001, 100)));
    }

    @Test
    void pinnedPlusCostUsesTheOfficialInputLengthPricingTiers() {
        var plus = profiles.require("dashscope-qwen37-plus-20260526-v1").profile();

        assertEquals(512_800L,
                ProviderCostEstimator.estimateMicrosCny(plus, new ProviderUsage(256_000, 100)));
        assertEquals(1_538_406L,
                ProviderCostEstimator.estimateMicrosCny(plus, new ProviderUsage(256_001, 100)));
    }

    @Test
    void preCallCostBoundIncludesPromptTaskOutputAndConservativeVisionTokens() {
        var plus = profiles.require("dashscope-qwen37-plus-20260526-v1").profile();
        var bounded = new ProviderInferenceRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                0, InferenceStage.STRUCTURE, plus, "Return JSON only.",
                "{\"mode\":\"IMAGE_ONLY\"}",
                List.of(new ProviderImage("b".repeat(64), "image/png", new byte[] {1}))
        );
        var oversized = new ProviderInferenceRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                0, InferenceStage.STRUCTURE, plus, "Return JSON only.",
                "x".repeat(100_000), List.of()
        );

        var boundedCost = ProviderCostEstimator.maximumRequestCostMicrosCny(bounded);
        assertTrue(boundedCost > 0 && boundedCost <= plus.maximumEstimatedCostMicrosCny());
        assertTrue(ProviderCostEstimator.maximumRequestCostMicrosCny(oversized)
                > plus.maximumEstimatedCostMicrosCny());

        var flash = profiles.require("dashscope-qwen37-flash-v1").profile();
        var tieredFlash = new ProviderInferenceRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                0, InferenceStage.STRUCTURE, flash, "Return JSON only.",
                "x".repeat(40_000), List.of()
        );
        assertTrue(ProviderCostEstimator.maximumRequestCostMicrosCny(tieredFlash)
                > flash.maximumEstimatedCostMicrosCny());

        var boundedPlus = new ProviderInferenceRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000005"),
                0, InferenceStage.STRUCTURE, plus, "Return JSON only.",
                "{\"mode\":\"COMBINED\"}", List.of()
        );
        var oversizedPlus = new ProviderInferenceRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000006"),
                0, InferenceStage.STRUCTURE, plus, "Return JSON only.",
                "x".repeat(100_000), List.of()
        );
        assertTrue(ProviderCostEstimator.maximumRequestCostMicrosCny(boundedPlus)
                <= plus.maximumEstimatedCostMicrosCny());
        assertTrue(ProviderCostEstimator.maximumRequestCostMicrosCny(oversizedPlus)
                > plus.maximumEstimatedCostMicrosCny());
    }

    @Test
    void tenImagesReserveTheNormalizedMaximumPixelCostBeforeCall() {
        var plus = profiles.require("dashscope-qwen37-plus-20260526-v1").profile();
        var image = new ProviderImage("c".repeat(64), "image/png", new byte[] {1});
        var request = new ProviderInferenceRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000007"),
                0, InferenceStage.STRUCTURE, plus, "Return JSON only.",
                "{\"mode\":\"IMAGE_ONLY\"}", java.util.Collections.nCopies(10, image)
        );

        assertTrue(ProviderCostEstimator.maximumRequestCostMicrosCny(request)
                > plus.maximumEstimatedCostMicrosCny());
    }

    @Test
    void responseContainsOnlyCandidateTextAndSafeProviderMetadata() {
        var response = new ProviderInferenceResponse(
                "{\"contractVersion\":\"renderweave-candidate/1.0\"}",
                "request-safe-id",
                "qwen3.8-max",
                new ProviderUsage(1_000, 500),
                "stop"
        );

        assertEquals("request-safe-id", response.providerRequestId());
        assertEquals(1_000, response.usage().inputTokens());
        assertThrows(IllegalArgumentException.class, () -> new ProviderInferenceResponse(
                "{}", "Authorization: Bearer secret", "qwen3.8-max", new ProviderUsage(1, 1), "stop"
        ));
    }
}
