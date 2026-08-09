package cn.hbads.renderweave.inference.profile;

import cn.hbads.renderweave.inference.input.InferenceMode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceProfileRegistryTest {
    @Test
    void p5ExposesReplayAndFourGuardedDashScopeProfiles() {
        var registry = new InferenceProfileRegistry();
        var resource = registry.require("replay-v1");
        var profile = resource.profile();

        assertEquals(Set.of(
                "replay-v1",
                "dashscope-qwen37-flash-v1",
                "dashscope-qwen37-plus-20260526-v1",
                "dashscope-qwen37-plus-20260526-prompt-v2",
                "dashscope-qwen38-max-v1"
        ), registry.profileIds());
        assertEquals("renderweave-inference-profile/1.0", profile.profileVersion());
        assertEquals("REPLAY", profile.provider());
        assertEquals("deterministic-synthetic-replay-v1", profile.model());
        assertFalse(profile.networkAllowed());
        assertEquals(Set.of(InferenceMode.values()), Set.copyOf(profile.supportedModes()));
        assertEquals(8_000, profile.lowConfidenceThresholdBps());
        assertEquals(2, profile.maximumRepairRounds());
        assertEquals(6, profile.maximumTotalCalls());
        assertEquals("REPLAY_ONLY", profile.certification());
        assertTrue(resource.snapshotJson().contains("\"networkAllowed\":false"));

        assertDashScopeProfile(
                registry.require("dashscope-qwen37-flash-v1").profile(),
                "qwen3.7-flash", "renderweave-schema-candidate-prompt/1.0",
                200_000L, 800_000L, 20_000L, "2026-08-08"
        );
        assertDashScopeProfile(
                registry.require("dashscope-qwen37-plus-20260526-v1").profile(),
                "qwen3.7-plus-2026-05-26", "renderweave-schema-candidate-prompt/1.0",
                2_000_000L, 8_000_000L, 200_000L,
                "2026-08-09"
        );
        assertDashScopeProfile(
                registry.require("dashscope-qwen37-plus-20260526-prompt-v2").profile(),
                "qwen3.7-plus-2026-05-26", "renderweave-schema-candidate-prompt/2.0",
                2_000_000L, 8_000_000L, 200_000L,
                "2026-08-09"
        );
        assertDashScopeProfile(
                registry.require("dashscope-qwen38-max-v1").profile(),
                "qwen3.8-max", "renderweave-schema-candidate-prompt/1.0",
                12_000_000L, 36_000_000L, 280_000L, "2026-08-08"
        );
        assertThrows(IllegalArgumentException.class, () -> registry.require("live-provider"));
    }

    private static void assertDashScopeProfile(
            InferenceProfile profile,
            String model,
            String promptVersion,
            long inputPrice,
            long outputPrice,
            long maximumCost,
            String pricingEffectiveDate
    ) {
        assertEquals("DASHSCOPE", profile.provider());
        assertEquals(model, profile.model());
        assertTrue(profile.networkAllowed());
        assertEquals("OPENAI_CHAT_COMPLETIONS", profile.providerProtocol());
        assertEquals(
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                profile.providerEndpoint()
        );
        assertEquals("DASHSCOPE_API_KEY", profile.apiKeyEnvironmentVariable());
        assertEquals(promptVersion, profile.promptVersion());
        assertEquals("JSON_OBJECT", profile.responseFormat());
        assertFalse(profile.thinkingEnabled());
        assertFalse(profile.toolsAllowed());
        assertFalse(profile.remoteMediaAllowed());
        assertEquals("SYNTHETIC_ONLY", profile.inputClassification());
        assertEquals(3, profile.maximumTotalCalls());
        assertEquals(4_096, profile.maximumOutputTokens());
        assertEquals(maximumCost, profile.maximumEstimatedCostMicrosCny());
        assertEquals(inputPrice, profile.inputMicrosCnyPerMillionTokens());
        assertEquals(outputPrice, profile.outputMicrosCnyPerMillionTokens());
        assertEquals(pricingEffectiveDate, profile.pricingEffectiveDate());
        assertEquals("EXPERIMENTAL", profile.certification());
    }
}
