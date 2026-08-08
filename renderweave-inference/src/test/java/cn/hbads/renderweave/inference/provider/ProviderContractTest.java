package cn.hbads.renderweave.inference.provider;

import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
