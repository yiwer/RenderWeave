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
    void exposesHistoricalProfilesAndKeepsExperimentalVisualProfilesWithheld() {
        var registry = new InferenceProfileRegistry();
        var resource = registry.require("replay-v1");
        var profile = resource.profile();

        assertEquals(Set.of(
                "replay-v1",
                "dashscope-qwen37-flash-v1",
                "dashscope-qwen37-plus-20260526-v1",
                "dashscope-qwen37-plus-20260526-prompt-v2",
                "dashscope-qwen37-plus-20260526-grounded-v1",
                "dashscope-qwen38-max-v1",
                "dashscope-qwen37-flash-product-v1",
                "dashscope-qwen37-plus-product-v1",
                "dashscope-qwen38-max-product-v1",
                "dashscope-qwen37-max-20260608-product-v1",
                "dashscope-qwen37-flash-product-v2",
                "dashscope-qwen37-plus-product-v2",
                "dashscope-qwen38-max-product-v2",
                "dashscope-qwen37-max-20260608-product-v2",
                "dashscope-qwen37-flash-product-v3",
                "dashscope-qwen37-plus-product-v3",
                "dashscope-qwen38-max-product-v3",
                "dashscope-qwen37-max-20260608-product-v3",
                "dashscope-qwen37-flash-product-v4",
                "dashscope-qwen37-plus-product-v4",
                "dashscope-qwen38-max-product-v4",
                "dashscope-qwen37-max-20260608-product-v4",
                "dashscope-qwen37-flash-product-v5",
                "dashscope-qwen37-plus-product-v5",
                "dashscope-qwen38-max-product-v5",
                "dashscope-qwen37-flash-product-v6-generic",
                "dashscope-qwen37-plus-product-v6-generic",
                "dashscope-qwen38-max-product-v6-generic",
                "dashscope-qwen37-flash-product-v6-transit-board",
                "dashscope-qwen37-plus-product-v6-transit-board",
                "dashscope-qwen38-max-product-v6-transit-board",
                "dashscope-qwen37-flash-product-v7-hybrid-generic",
                "dashscope-qwen37-plus-product-v7-hybrid-generic",
                "dashscope-qwen38-max-product-v7-hybrid-generic"
        ), registry.profileIds());
        assertEquals(java.util.List.of(
                "dashscope-qwen37-flash-product-v4",
                "dashscope-qwen37-plus-product-v4",
                "dashscope-qwen38-max-product-v4",
                "dashscope-qwen37-max-20260608-product-v4"
        ), registry.productLiveProfiles().stream().map(item -> item.profile().profileId()).toList());
        assertEquals(java.util.List.of(
                "qwen3.7-flash", "qwen3.7-plus", "qwen3.8-max", "qwen3.7-max-2026-06-08"
        ), registry.productLiveProfiles().stream().map(item -> item.profile().model()).toList());
        assertEquals(java.util.List.of(
                "dashscope-qwen37-flash-product-v5",
                "dashscope-qwen37-plus-product-v5",
                "dashscope-qwen38-max-product-v5"
        ), registry.visualNextProfiles().stream()
                .map(item -> item.profile().profile().profileId()).toList());
        assertEquals(java.util.List.of(
                "qwen3.7-flash", "qwen3.7-plus", "qwen3.8-max"
        ), registry.visualNextProfiles().stream()
                .map(item -> item.capability().capability().model()).toList());
        assertEquals(6, registry.visualGroundingProfiles().size());
        assertEquals(java.util.List.of(
                "dashscope-qwen37-flash-product-v7-hybrid-generic",
                "dashscope-qwen37-plus-product-v7-hybrid-generic",
                "dashscope-qwen38-max-product-v7-hybrid-generic"
        ), registry.visualHybridProfiles().stream()
                .map(item -> item.profile().profile().profileId()).toList());
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
        assertFalse(resource.snapshotJson().contains("visualHintPackVersion"));
        assertFalse(registry.require("dashscope-qwen37-flash-product-v4").snapshotJson()
                .contains("visualHintPackVersion"));
        assertTrue(registry.require("dashscope-qwen37-flash-product-v6-generic").snapshotJson()
                .contains("\"visualHintPackVersion\":\"renderweave-visual-hint-pack/generic/1.0\""));
        assertFalse(registry.require("dashscope-qwen37-flash-product-v6-generic").snapshotJson()
                .contains("documentVisionCapabilityId"));
        assertTrue(registry.require("dashscope-qwen37-flash-product-v7-hybrid-generic").snapshotJson()
                .contains("\"documentVisionPromptVersion\":\"renderweave-document-vision-observations-prompt/1.0\""));

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
        var grounded = registry.require(
                "dashscope-qwen37-plus-20260526-grounded-v1"
        ).profile();
        assertDashScopeProfile(
                grounded,
                "qwen3.7-plus-2026-05-26", "renderweave-schema-candidate-prompt/3.0",
                2_000_000L, 8_000_000L, 200_000L,
                "2026-08-09"
        );
        assertEquals("renderweave-inference-pipeline/2.0", grounded.pipelineVersion());
        assertDashScopeProfile(
                registry.require("dashscope-qwen38-max-v1").profile(),
                "qwen3.8-max", "renderweave-schema-candidate-prompt/1.0",
                12_000_000L, 36_000_000L, 280_000L, "2026-08-08"
        );
        assertHistoricalProductProfile(
                registry, "dashscope-qwen37-flash-product-v1", 250_000L
        );
        assertHistoricalProductProfile(
                registry, "dashscope-qwen37-plus-product-v1", 500_000L
        );
        assertHistoricalProductProfile(
                registry, "dashscope-qwen38-max-product-v1", 2_500_000L
        );
        assertHistoricalProductProfile(
                registry, "dashscope-qwen37-max-20260608-product-v1", 2_500_000L
        );
        assertHistoricalProductV2(registry, "dashscope-qwen37-flash-product-v2");
        assertHistoricalProductV2(registry, "dashscope-qwen37-plus-product-v2");
        assertHistoricalProductV2(registry, "dashscope-qwen38-max-product-v2");
        assertHistoricalProductV2(registry, "dashscope-qwen37-max-20260608-product-v2");
        assertHistoricalProductV3(registry, "dashscope-qwen37-flash-product-v3");
        assertHistoricalProductV3(registry, "dashscope-qwen37-plus-product-v3");
        assertHistoricalProductV3(registry, "dashscope-qwen38-max-product-v3");
        assertHistoricalProductV3(registry, "dashscope-qwen37-max-20260608-product-v3");
        assertProductProfile(registry, "dashscope-qwen37-flash-product-v4", "qwen3.7-flash", 2_000_000L);
        assertProductProfile(registry, "dashscope-qwen37-plus-product-v4", "qwen3.7-plus", 2_000_000L);
        assertProductProfile(registry, "dashscope-qwen38-max-product-v4", "qwen3.8-max", 2_000_000L);
        assertProductProfile(
                registry, "dashscope-qwen37-max-20260608-product-v4",
                "qwen3.7-max-2026-06-08", 2_000_000L
        );
        assertVisualNextProfile(registry, "dashscope-qwen37-flash-product-v5", "qwen3.7-flash");
        assertVisualNextProfile(registry, "dashscope-qwen37-plus-product-v5", "qwen3.7-plus");
        assertVisualNextProfile(registry, "dashscope-qwen38-max-product-v5", "qwen3.8-max");
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-flash-product-v6-generic", "qwen3.7-flash",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-plus-product-v6-transit-board", "qwen3.7-plus",
                InferencePromptRegistry.VISUAL_HINT_TRANSIT_BOARD_V1
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen38-max-product-v6-generic", "qwen3.8-max",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1
        );
        assertHybridVisualProfile(
                registry, "dashscope-qwen37-flash-product-v7-hybrid-generic", "qwen3.7-flash"
        );
        assertHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v7-hybrid-generic", "qwen3.7-plus"
        );
        assertHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v7-hybrid-generic", "qwen3.8-max"
        );
        assertThrows(IllegalArgumentException.class, () -> registry.require("live-provider"));
    }

    private static void assertHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.2", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HINT_GENERIC_V1, profile.visualHintPackVersion());
        assertEquals(
                "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1",
                profile.documentVisionCapabilityId()
        );
        assertEquals(
                InferencePromptRegistry.DOCUMENT_VISION_OBSERVATIONS_V1,
                profile.documentVisionPromptVersion()
        );
        assertEquals(java.util.List.of(InferenceMode.IMAGE_ONLY), profile.supportedModes());
        assertEquals(0, profile.maximumRepairRounds());
        assertEquals(5, profile.maximumTotalCalls());
        assertEquals(240, profile.stageTimeoutSeconds());
        assertEquals("EXPERIMENTAL", profile.certification());
    }

    private static void assertGroundedVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model,
            String hintPack
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.1", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V2, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V2, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V2, profile.bindingPromptVersion());
        assertEquals(hintPack, profile.visualHintPackVersion());
        assertEquals(java.util.List.of(InferenceMode.IMAGE_ONLY), profile.supportedModes());
        assertEquals(0, profile.maximumRepairRounds());
        assertEquals(5, profile.maximumTotalCalls());
        assertEquals(240, profile.stageTimeoutSeconds());
        assertEquals("EXPERIMENTAL", profile.certification());
    }

    private static void assertVisualNextProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualNextProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.0", profile.pipelineVersion());
        assertEquals(java.util.List.of(InferenceMode.IMAGE_ONLY), profile.supportedModes());
        assertEquals(0, profile.maximumRepairRounds());
        assertEquals(5, profile.maximumTotalCalls());
        assertEquals(240, profile.stageTimeoutSeconds());
        assertEquals(8_192, profile.maximumOutputTokens());
        assertEquals("EXPERIMENTAL", profile.certification());
    }

    private static void assertProductProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model,
            long maximumCost
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("USER_CONFIRMED", profile.inputClassification());
        assertEquals("renderweave-inference-pipeline/3.0", profile.pipelineVersion());
        assertEquals("renderweave-schema-candidate-prompt/5.0", profile.promptVersion());
        assertEquals("renderweave-visual-elements-prompt/1.0", profile.elementPromptVersion());
        assertEquals("renderweave-visual-hierarchy-prompt/1.0", profile.hierarchyPromptVersion());
        assertEquals("renderweave-visual-bindings-prompt/1.0", profile.bindingPromptVersion());
        assertEquals(5, profile.maximumTotalCalls());
        assertEquals(1, profile.maximumRepairRounds());
        assertEquals(240, profile.stageTimeoutSeconds());
        assertEquals(8_192, profile.maximumOutputTokens());
        assertEquals(maximumCost, profile.maximumEstimatedCostMicrosCny());
        assertEquals("EXPERIMENTAL", profile.certification());
    }

    private static void assertHistoricalProductV3(InferenceProfileRegistry registry, String profileId) {
        var profile = registry.require(profileId).profile();
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals("renderweave-inference-pipeline/3.0", profile.pipelineVersion());
        assertEquals("renderweave-schema-candidate-prompt/5.0", profile.promptVersion());
        assertEquals(90, profile.stageTimeoutSeconds());
    }

    private static void assertHistoricalProductV2(InferenceProfileRegistry registry, String profileId) {
        var profile = registry.require(profileId).profile();
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals("renderweave-inference-pipeline/2.0", profile.pipelineVersion());
        assertEquals("renderweave-schema-candidate-prompt/4.0", profile.promptVersion());
        assertEquals(3, profile.maximumTotalCalls());
    }

    private static void assertHistoricalProductProfile(
            InferenceProfileRegistry registry,
            String profileId,
            long maximumCost
    ) {
        var profile = registry.require(profileId).profile();
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals("renderweave-schema-candidate-prompt/3.0", profile.promptVersion());
        assertEquals(maximumCost, profile.maximumEstimatedCostMicrosCny());
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
