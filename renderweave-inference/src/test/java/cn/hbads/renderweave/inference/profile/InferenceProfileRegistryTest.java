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
    void productCatalogUsesV42WithoutMutatingTheV41Snapshots() {
        var registry = new InferenceProfileRegistry();

        assertEquals(java.util.List.of(
                "dashscope-qwen37-plus-product-v42-hybrid-generic",
                "dashscope-qwen38-max-product-v42-hybrid-generic",
                "dashscope-qwen37-flash-product-v42-hybrid-generic"
        ), registry.productLiveProfiles().stream().map(item -> item.profile().profileId()).toList());
        assertEquals(java.util.List.of(
                "qwen3.7-plus", "qwen3.8-max", "qwen3.7-flash"
        ), registry.productLiveProfiles().stream().map(item -> item.profile().model()).toList());
        assertPricingInherited(
                registry,
                "dashscope-qwen37-plus-product-v41-hybrid-generic",
                "dashscope-qwen37-plus-product-v42-hybrid-generic"
        );
        assertPricingInherited(
                registry,
                "dashscope-qwen38-max-product-v41-hybrid-generic",
                "dashscope-qwen38-max-product-v42-hybrid-generic"
        );
        assertPricingInherited(
                registry,
                "dashscope-qwen37-flash-product-v41-hybrid-generic",
                "dashscope-qwen37-flash-product-v42-hybrid-generic"
        );

        var genericFlash = registry.require(
                "dashscope-qwen37-flash-product-v42-hybrid-generic"
        ).profile();
        assertEquals("renderweave-inference-pipeline/4.28", genericFlash.pipelineVersion());
        assertEquals("qwen3.7-flash", genericFlash.model());
        assertEquals(
                "qwen3.7-flash-2026-07-15",
                registry.require(
                        "dashscope-qwen37-flash-20260715-product-v40-hybrid-generic"
                ).profile().model()
        );
        assertFalse(registry.isProductLiveProfile(
                "dashscope-qwen37-flash-20260715-product-v40-hybrid-generic"
        ));
        assertFalse(registry.isProductLiveProfile(
                "dashscope-qwen37-flash-product-v40-hybrid-generic"
        ));
        assertFalse(registry.isProductLiveProfile(
                "dashscope-qwen37-flash-product-v41-hybrid-generic"
        ));
    }

    @Test
    void exposesHistoricalProfilesAndPromotesOnlyTheCurrentV42Catalog() {
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
                "dashscope-qwen38-max-product-v7-hybrid-generic",
                "dashscope-qwen37-flash-product-v8-generic",
                "dashscope-qwen37-plus-product-v8-generic",
                "dashscope-qwen38-max-product-v8-generic",
                "dashscope-qwen37-flash-product-v9-generic",
                "dashscope-qwen37-plus-product-v9-generic",
                "dashscope-qwen38-max-product-v9-generic",
                "dashscope-qwen37-flash-product-v10-generic",
                "dashscope-qwen37-plus-product-v10-generic",
                "dashscope-qwen38-max-product-v10-generic",
                "dashscope-qwen37-flash-product-v11-generic",
                "dashscope-qwen37-plus-product-v11-generic",
                "dashscope-qwen38-max-product-v11-generic",
                "dashscope-qwen37-flash-product-v12-generic",
                "dashscope-qwen37-plus-product-v12-generic",
                "dashscope-qwen38-max-product-v12-generic",
                "dashscope-qwen37-flash-20260715-product-v13-generic",
                "dashscope-qwen37-flash-20260715-product-v14-generic",
                "dashscope-qwen37-plus-product-v14-generic",
                "dashscope-qwen38-max-product-v14-generic",
                "dashscope-qwen37-flash-20260715-product-v15-generic",
                "dashscope-qwen37-plus-product-v15-generic",
                "dashscope-qwen38-max-product-v15-generic",
                "dashscope-qwen37-flash-20260715-product-v16-generic",
                "dashscope-qwen37-plus-product-v16-generic",
                "dashscope-qwen38-max-product-v16-generic",
                "dashscope-qwen37-flash-20260715-product-v17-generic",
                "dashscope-qwen37-plus-product-v17-generic",
                "dashscope-qwen38-max-product-v17-generic",
                "dashscope-qwen37-flash-20260715-product-v18-generic",
                "dashscope-qwen37-plus-product-v18-generic",
                "dashscope-qwen38-max-product-v18-generic",
                "dashscope-qwen37-flash-20260715-product-v19-generic",
                "dashscope-qwen37-plus-product-v19-generic",
                "dashscope-qwen38-max-product-v19-generic",
                "dashscope-qwen37-flash-20260715-product-v20-generic",
                "dashscope-qwen37-plus-product-v20-generic",
                "dashscope-qwen38-max-product-v20-generic",
                "dashscope-qwen37-flash-20260715-product-v21-generic",
                "dashscope-qwen37-plus-product-v21-generic",
                "dashscope-qwen38-max-product-v21-generic",
                "dashscope-qwen37-flash-20260715-product-v22-generic",
                "dashscope-qwen37-plus-product-v22-generic",
                "dashscope-qwen38-max-product-v22-generic",
                "dashscope-qwen37-flash-20260715-product-v23-hybrid-generic",
                "dashscope-qwen37-plus-product-v23-hybrid-generic",
                "dashscope-qwen38-max-product-v23-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v24-hybrid-generic",
                "dashscope-qwen37-plus-product-v24-hybrid-generic",
                "dashscope-qwen38-max-product-v24-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v25-hybrid-generic",
                "dashscope-qwen37-plus-product-v25-hybrid-generic",
                "dashscope-qwen38-max-product-v25-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v26-hybrid-generic",
                "dashscope-qwen37-plus-product-v26-hybrid-generic",
                "dashscope-qwen38-max-product-v26-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v27-hybrid-generic",
                "dashscope-qwen37-plus-product-v27-hybrid-generic",
                "dashscope-qwen38-max-product-v27-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v28-hybrid-generic",
                "dashscope-qwen37-plus-product-v28-hybrid-generic",
                "dashscope-qwen38-max-product-v28-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v29-hybrid-generic",
                "dashscope-qwen37-plus-product-v29-hybrid-generic",
                "dashscope-qwen38-max-product-v29-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v30-hybrid-generic",
                "dashscope-qwen37-plus-product-v30-hybrid-generic",
                "dashscope-qwen38-max-product-v30-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v31-hybrid-generic",
                "dashscope-qwen37-plus-product-v31-hybrid-generic",
                "dashscope-qwen38-max-product-v31-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v32-hybrid-generic",
                "dashscope-qwen37-plus-product-v32-hybrid-generic",
                "dashscope-qwen38-max-product-v32-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v33-hybrid-generic",
                "dashscope-qwen37-plus-product-v33-hybrid-generic",
                "dashscope-qwen38-max-product-v33-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v34-hybrid-generic",
                "dashscope-qwen37-plus-product-v34-hybrid-generic",
                "dashscope-qwen38-max-product-v34-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v35-hybrid-generic",
                "dashscope-qwen37-plus-product-v35-hybrid-generic",
                "dashscope-qwen38-max-product-v35-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v36-hybrid-generic",
                "dashscope-qwen37-plus-product-v36-hybrid-generic",
                "dashscope-qwen38-max-product-v36-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v37-hybrid-generic",
                "dashscope-qwen37-plus-product-v37-hybrid-generic",
                "dashscope-qwen38-max-product-v37-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v38-hybrid-generic",
                "dashscope-qwen37-plus-product-v38-hybrid-generic",
                "dashscope-qwen38-max-product-v38-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v39-hybrid-generic",
                "dashscope-qwen37-plus-product-v39-hybrid-generic",
                "dashscope-qwen38-max-product-v39-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v40-hybrid-generic",
                "dashscope-qwen37-flash-product-v40-hybrid-generic",
                "dashscope-qwen37-plus-product-v40-hybrid-generic",
                "dashscope-qwen38-max-product-v40-hybrid-generic",
                "dashscope-qwen37-flash-product-v41-hybrid-generic",
                "dashscope-qwen37-plus-product-v41-hybrid-generic",
                "dashscope-qwen38-max-product-v41-hybrid-generic",
                "dashscope-qwen37-flash-product-v42-hybrid-generic",
                "dashscope-qwen37-plus-product-v42-hybrid-generic",
                "dashscope-qwen38-max-product-v42-hybrid-generic"
        ), registry.profileIds());
        assertEquals(java.util.List.of(
                "dashscope-qwen37-plus-product-v42-hybrid-generic",
                "dashscope-qwen38-max-product-v42-hybrid-generic",
                "dashscope-qwen37-flash-product-v42-hybrid-generic"
        ), registry.productLiveProfiles().stream().map(item -> item.profile().profileId()).toList());
        assertEquals(java.util.List.of(
                "qwen3.7-plus", "qwen3.8-max", "qwen3.7-flash"
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
        assertEquals(110, registry.visualGroundingProfiles().size());
        assertEquals(java.util.List.of(
                "dashscope-qwen37-flash-product-v7-hybrid-generic",
                "dashscope-qwen37-plus-product-v7-hybrid-generic",
                "dashscope-qwen38-max-product-v7-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v23-hybrid-generic",
                "dashscope-qwen37-plus-product-v23-hybrid-generic",
                "dashscope-qwen38-max-product-v23-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v24-hybrid-generic",
                "dashscope-qwen37-plus-product-v24-hybrid-generic",
                "dashscope-qwen38-max-product-v24-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v25-hybrid-generic",
                "dashscope-qwen37-plus-product-v25-hybrid-generic",
                "dashscope-qwen38-max-product-v25-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v26-hybrid-generic",
                "dashscope-qwen37-plus-product-v26-hybrid-generic",
                "dashscope-qwen38-max-product-v26-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v27-hybrid-generic",
                "dashscope-qwen37-plus-product-v27-hybrid-generic",
                "dashscope-qwen38-max-product-v27-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v28-hybrid-generic",
                "dashscope-qwen37-plus-product-v28-hybrid-generic",
                "dashscope-qwen38-max-product-v28-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v29-hybrid-generic",
                "dashscope-qwen37-plus-product-v29-hybrid-generic",
                "dashscope-qwen38-max-product-v29-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v30-hybrid-generic",
                "dashscope-qwen37-plus-product-v30-hybrid-generic",
                "dashscope-qwen38-max-product-v30-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v31-hybrid-generic",
                "dashscope-qwen37-plus-product-v31-hybrid-generic",
                "dashscope-qwen38-max-product-v31-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v32-hybrid-generic",
                "dashscope-qwen37-plus-product-v32-hybrid-generic",
                "dashscope-qwen38-max-product-v32-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v33-hybrid-generic",
                "dashscope-qwen37-plus-product-v33-hybrid-generic",
                "dashscope-qwen38-max-product-v33-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v34-hybrid-generic",
                "dashscope-qwen37-plus-product-v34-hybrid-generic",
                "dashscope-qwen38-max-product-v34-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v35-hybrid-generic",
                "dashscope-qwen37-plus-product-v35-hybrid-generic",
                "dashscope-qwen38-max-product-v35-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v36-hybrid-generic",
                "dashscope-qwen37-plus-product-v36-hybrid-generic",
                "dashscope-qwen38-max-product-v36-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v37-hybrid-generic",
                "dashscope-qwen37-plus-product-v37-hybrid-generic",
                "dashscope-qwen38-max-product-v37-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v38-hybrid-generic",
                "dashscope-qwen37-plus-product-v38-hybrid-generic",
                "dashscope-qwen38-max-product-v38-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v39-hybrid-generic",
                "dashscope-qwen37-plus-product-v39-hybrid-generic",
                "dashscope-qwen38-max-product-v39-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v40-hybrid-generic",
                "dashscope-qwen37-flash-product-v40-hybrid-generic",
                "dashscope-qwen37-plus-product-v40-hybrid-generic",
                "dashscope-qwen38-max-product-v40-hybrid-generic",
                "dashscope-qwen37-flash-product-v41-hybrid-generic",
                "dashscope-qwen37-plus-product-v41-hybrid-generic",
                "dashscope-qwen38-max-product-v41-hybrid-generic",
                "dashscope-qwen37-flash-product-v42-hybrid-generic",
                "dashscope-qwen37-plus-product-v42-hybrid-generic",
                "dashscope-qwen38-max-product-v42-hybrid-generic"
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
        assertHistoricalProductV4(registry, "dashscope-qwen37-flash-product-v4", "qwen3.7-flash", 2_000_000L);
        assertHistoricalProductV4(registry, "dashscope-qwen37-plus-product-v4", "qwen3.7-plus", 2_000_000L);
        assertHistoricalProductV4(registry, "dashscope-qwen38-max-product-v4", "qwen3.8-max", 2_000_000L);
        assertHistoricalProductV4(
                registry, "dashscope-qwen37-max-20260608-product-v4",
                "qwen3.7-max-2026-06-08", 2_000_000L
        );
        assertVisualNextProfile(registry, "dashscope-qwen37-flash-product-v5", "qwen3.7-flash");
        assertVisualNextProfile(registry, "dashscope-qwen37-plus-product-v5", "qwen3.7-plus");
        assertVisualNextProfile(registry, "dashscope-qwen38-max-product-v5", "qwen3.8-max");
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-flash-product-v6-generic", "qwen3.7-flash",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V2
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-plus-product-v6-transit-board", "qwen3.7-plus",
                InferencePromptRegistry.VISUAL_HINT_TRANSIT_BOARD_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V2
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen38-max-product-v6-generic", "qwen3.8-max",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V2
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-flash-product-v8-generic", "qwen3.7-flash",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V3
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-plus-product-v8-generic", "qwen3.7-plus",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V3
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen38-max-product-v8-generic", "qwen3.8-max",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V3
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-flash-product-v9-generic", "qwen3.7-flash",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V3
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-plus-product-v9-generic", "qwen3.7-plus",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V3
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen38-max-product-v9-generic", "qwen3.8-max",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V3
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-flash-product-v10-generic", "qwen3.7-flash",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V4
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-plus-product-v10-generic", "qwen3.7-plus",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V4
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen38-max-product-v10-generic", "qwen3.8-max",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V4
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-flash-product-v11-generic", "qwen3.7-flash",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V5
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-plus-product-v11-generic", "qwen3.7-plus",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V5
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen38-max-product-v11-generic", "qwen3.8-max",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V5
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-flash-product-v12-generic", "qwen3.7-flash",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V6
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-plus-product-v12-generic", "qwen3.7-plus",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V6
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen38-max-product-v12-generic", "qwen3.8-max",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V6
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v13-generic",
                "qwen3.7-flash-2026-07-15",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V6
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v14-generic",
                "qwen3.7-flash-2026-07-15",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V6
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen37-plus-product-v14-generic", "qwen3.7-plus",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V6
        );
        assertGroundedVisualProfile(
                registry, "dashscope-qwen38-max-product-v14-generic", "qwen3.8-max",
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.VISUAL_ELEMENTS_V6
        );
        assertEvidenceDerivedVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v16-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertEvidenceDerivedVisualProfile(
                registry, "dashscope-qwen37-plus-product-v16-generic", "qwen3.7-plus"
        );
        assertEvidenceDerivedVisualProfile(
                registry, "dashscope-qwen38-max-product-v16-generic", "qwen3.8-max"
        );
        assertRegionOwnedVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v17-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertRegionOwnedVisualProfile(
                registry, "dashscope-qwen37-plus-product-v17-generic", "qwen3.7-plus"
        );
        assertRegionOwnedVisualProfile(
                registry, "dashscope-qwen38-max-product-v17-generic", "qwen3.8-max"
        );
        assertDiagnosticHierarchyVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v18-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertDiagnosticHierarchyVisualProfile(
                registry, "dashscope-qwen37-plus-product-v18-generic", "qwen3.7-plus"
        );
        assertDiagnosticHierarchyVisualProfile(
                registry, "dashscope-qwen38-max-product-v18-generic", "qwen3.8-max"
        );
        assertSupportNormalizedHierarchyVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v19-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertSupportNormalizedHierarchyVisualProfile(
                registry, "dashscope-qwen37-plus-product-v19-generic", "qwen3.7-plus"
        );
        assertSupportNormalizedHierarchyVisualProfile(
                registry, "dashscope-qwen38-max-product-v19-generic", "qwen3.8-max"
        );
        assertRegionNormalizedHierarchyVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v20-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertRegionNormalizedHierarchyVisualProfile(
                registry, "dashscope-qwen37-plus-product-v20-generic", "qwen3.7-plus"
        );
        assertRegionNormalizedHierarchyVisualProfile(
                registry, "dashscope-qwen38-max-product-v20-generic", "qwen3.8-max"
        );
        assertConnectionNormalizedHierarchyVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v21-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertConnectionNormalizedHierarchyVisualProfile(
                registry, "dashscope-qwen37-plus-product-v21-generic", "qwen3.7-plus"
        );
        assertConnectionNormalizedHierarchyVisualProfile(
                registry, "dashscope-qwen38-max-product-v21-generic", "qwen3.8-max"
        );
        assertSupportOwnerNormalizedHierarchyVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v22-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertSupportOwnerNormalizedHierarchyVisualProfile(
                registry, "dashscope-qwen37-plus-product-v22-generic", "qwen3.7-plus"
        );
        assertSupportOwnerNormalizedHierarchyVisualProfile(
                registry, "dashscope-qwen38-max-product-v22-generic", "qwen3.8-max"
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
        assertSupportOwnerHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v23-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertSupportOwnerHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v23-hybrid-generic", "qwen3.7-plus"
        );
        assertSupportOwnerHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v23-hybrid-generic", "qwen3.8-max"
        );
        assertBoundedObservationHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v24-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertBoundedObservationHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v24-hybrid-generic", "qwen3.7-plus"
        );
        assertBoundedObservationHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v24-hybrid-generic", "qwen3.8-max"
        );
        assertLeafEvidenceVerifiedHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v25-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertLeafEvidenceVerifiedHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v25-hybrid-generic", "qwen3.7-plus"
        );
        assertLeafEvidenceVerifiedHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v25-hybrid-generic", "qwen3.8-max"
        );
        assertEnclosingSupportOwnerHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v26-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertEnclosingSupportOwnerHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v26-hybrid-generic", "qwen3.7-plus"
        );
        assertEnclosingSupportOwnerHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v26-hybrid-generic", "qwen3.8-max"
        );
        assertSourceAncestorSupportOwnerHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v27-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertSourceAncestorSupportOwnerHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v27-hybrid-generic", "qwen3.7-plus"
        );
        assertSourceAncestorSupportOwnerHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v27-hybrid-generic", "qwen3.8-max"
        );
        assertMinimalEntityOwnershipHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v28-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertMinimalEntityOwnershipHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v28-hybrid-generic", "qwen3.7-plus"
        );
        assertMinimalEntityOwnershipHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v28-hybrid-generic", "qwen3.8-max"
        );
        assertGroupRegionCardinalityHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v29-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertGroupRegionCardinalityHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v29-hybrid-generic", "qwen3.7-plus"
        );
        assertGroupRegionCardinalityHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v29-hybrid-generic", "qwen3.8-max"
        );
        assertEvidenceOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v30-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertEvidenceOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v30-hybrid-generic", "qwen3.7-plus"
        );
        assertEvidenceOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v30-hybrid-generic", "qwen3.8-max"
        );
        assertRepeatedItemSlotOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v31-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertRepeatedItemSlotOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v31-hybrid-generic", "qwen3.7-plus"
        );
        assertRepeatedItemSlotOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v31-hybrid-generic", "qwen3.8-max"
        );
        assertEmptySupportOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v32-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertEmptySupportOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v32-hybrid-generic", "qwen3.7-plus"
        );
        assertEmptySupportOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v32-hybrid-generic", "qwen3.8-max"
        );
        assertUnknownSupportOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v33-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertUnknownSupportOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v33-hybrid-generic", "qwen3.7-plus"
        );
        assertUnknownSupportOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v33-hybrid-generic", "qwen3.8-max"
        );
        assertUniqueRegionParentNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v34-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertUniqueRegionParentNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v34-hybrid-generic", "qwen3.7-plus"
        );
        assertUniqueRegionParentNormalizedHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v34-hybrid-generic", "qwen3.8-max"
        );
        assertEmptySourceAncestorSupportOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v35-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertEmptySourceAncestorSupportOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v35-hybrid-generic", "qwen3.7-plus"
        );
        assertEmptySourceAncestorSupportOwnerNormalizedHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v35-hybrid-generic", "qwen3.8-max"
        );
        assertStructuralRegionKindNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v36-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertStructuralRegionKindNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v36-hybrid-generic", "qwen3.7-plus"
        );
        assertStructuralRegionKindNormalizedHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v36-hybrid-generic", "qwen3.8-max"
        );
        assertConstraintRegionKindNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v37-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertConstraintRegionKindNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v37-hybrid-generic", "qwen3.7-plus"
        );
        assertConstraintRegionKindNormalizedHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v37-hybrid-generic", "qwen3.8-max"
        );
        assertAncestorRegionParentNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v38-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertAncestorRegionParentNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v38-hybrid-generic", "qwen3.7-plus"
        );
        assertAncestorRegionParentNormalizedHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v38-hybrid-generic", "qwen3.8-max"
        );
        assertGappedReadingOrderNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v39-hybrid-generic",
                "qwen3.7-flash-2026-07-15"
        );
        assertGappedReadingOrderNormalizedHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v39-hybrid-generic", "qwen3.7-plus"
        );
        assertGappedReadingOrderNormalizedHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v39-hybrid-generic", "qwen3.8-max"
        );
        assertReadingOrderDiagnosticHybridVisualProfile(
                registry, "dashscope-qwen37-flash-20260715-product-v40-hybrid-generic",
                "qwen3.7-flash-2026-07-15", false
        );
        assertReadingOrderDiagnosticHybridVisualProfile(
                registry, "dashscope-qwen37-flash-product-v40-hybrid-generic", "qwen3.7-flash", false
        );
        assertReadingOrderDiagnosticHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v40-hybrid-generic", "qwen3.7-plus", false
        );
        assertReadingOrderDiagnosticHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v40-hybrid-generic", "qwen3.8-max", false
        );
        assertEarlyRelationshipGroupPrerequisiteHybridVisualProfile(
                registry, "dashscope-qwen37-flash-product-v41-hybrid-generic", "qwen3.7-flash",
                false, 5, 240, 8_192
        );
        assertEarlyRelationshipGroupPrerequisiteHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v41-hybrid-generic", "qwen3.7-plus",
                false, 5, 240, 8_192
        );
        assertEarlyRelationshipGroupPrerequisiteHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v41-hybrid-generic", "qwen3.8-max",
                false, 5, 240, 8_192
        );
        assertEarlyRelationshipGroupPrerequisiteHybridVisualProfile(
                registry, "dashscope-qwen37-flash-product-v42-hybrid-generic", "qwen3.7-flash",
                true, 7, 360, 16_384
        );
        assertEarlyRelationshipGroupPrerequisiteHybridVisualProfile(
                registry, "dashscope-qwen37-plus-product-v42-hybrid-generic", "qwen3.7-plus",
                true, 7, 360, 16_384
        );
        assertEarlyRelationshipGroupPrerequisiteHybridVisualProfile(
                registry, "dashscope-qwen38-max-product-v42-hybrid-generic", "qwen3.8-max",
                true, 7, 360, 8_192
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
            String hintPack,
            String elementPromptVersion
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.1", profile.pipelineVersion());
        assertEquals(elementPromptVersion, profile.elementPromptVersion());
        var hierarchyPrompt = profileId.contains("-v14-")
                ? InferencePromptRegistry.VISUAL_HIERARCHY_V5
                : profileId.contains("-v11-") || profileId.contains("-v12-")
                || profileId.contains("-v13-")
                ? InferencePromptRegistry.VISUAL_HIERARCHY_V4
                : profileId.contains("-v9-") || profileId.contains("-v10-")
                ? InferencePromptRegistry.VISUAL_HIERARCHY_V3
                : InferencePromptRegistry.VISUAL_HIERARCHY_V2;
        assertEquals(hierarchyPrompt, profile.hierarchyPromptVersion());
        assertEquals(
                profileId.contains("-v11-") || profileId.contains("-v12-")
                        || profileId.contains("-v13-") || profileId.contains("-v14-")
                        ? InferencePromptRegistry.VISUAL_BINDINGS_V3
                        : InferencePromptRegistry.VISUAL_BINDINGS_V2,
                profile.bindingPromptVersion()
        );
        assertEquals(hintPack, profile.visualHintPackVersion());
        assertEquals(java.util.List.of(InferenceMode.IMAGE_ONLY), profile.supportedModes());
        assertEquals(0, profile.maximumRepairRounds());
        assertEquals(5, profile.maximumTotalCalls());
        assertEquals(240, profile.stageTimeoutSeconds());
        assertEquals("EXPERIMENTAL", profile.certification());
    }

    private static void assertSupportOwnerHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.10", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V8, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertBoundedObservationHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.11", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V8, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertLeafEvidenceVerifiedHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.12", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V8, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertEnclosingSupportOwnerHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.13", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V8, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertSourceAncestorSupportOwnerHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.14", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V8, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertMinimalEntityOwnershipHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.15", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V8, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertGroupRegionCardinalityHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.16", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V9, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertEvidenceOwnerNormalizedHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.17", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V9, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertRepeatedItemSlotOwnerNormalizedHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.18", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V9, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertEmptySupportOwnerNormalizedHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.19", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V9, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertUnknownSupportOwnerNormalizedHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.20", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V9, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertUniqueRegionParentNormalizedHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.21", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V9, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertEmptySourceAncestorSupportOwnerNormalizedHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.22", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V9, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertStructuralRegionKindNormalizedHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.23", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V9, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertConstraintRegionKindNormalizedHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.24", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V9, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertAncestorRegionParentNormalizedHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.25", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V9, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertGappedReadingOrderNormalizedHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.26", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V9, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertReadingOrderDiagnosticHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model,
            boolean productLive
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertEquals(productLive, registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.27", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V10, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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

    private static void assertEarlyRelationshipGroupPrerequisiteHybridVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model,
            boolean productLive,
            int maximumTotalCalls,
            int stageTimeoutSeconds,
            int maximumOutputTokens
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertTrue(registry.isVisualHybridProfile(profileId));
        assertEquals(productLive, registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.28", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V10, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
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
        assertEquals(maximumTotalCalls, profile.maximumTotalCalls());
        assertEquals(stageTimeoutSeconds, profile.stageTimeoutSeconds());
        assertEquals(maximumOutputTokens, profile.maximumOutputTokens());
        assertEquals("EXPERIMENTAL", profile.certification());
    }

    private static void assertPricingInherited(
            InferenceProfileRegistry registry,
            String predecessorProfileId,
            String successorProfileId
    ) {
        var predecessor = registry.require(predecessorProfileId).profile();
        var successor = registry.require(successorProfileId).profile();
        assertEquals(predecessor.maximumEstimatedCostMicrosCny(), successor.maximumEstimatedCostMicrosCny());
        assertEquals(predecessor.inputMicrosCnyPerMillionTokens(), successor.inputMicrosCnyPerMillionTokens());
        assertEquals(predecessor.outputMicrosCnyPerMillionTokens(), successor.outputMicrosCnyPerMillionTokens());
        assertEquals(predecessor.pricingEffectiveDate(), successor.pricingEffectiveDate());
    }

    private static void assertEvidenceDerivedVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.3", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V7, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V5, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HINT_GENERIC_V1, profile.visualHintPackVersion());
        assertEquals(java.util.List.of(InferenceMode.IMAGE_ONLY), profile.supportedModes());
        assertEquals(0, profile.maximumRepairRounds());
        assertEquals(5, profile.maximumTotalCalls());
        assertEquals(240, profile.stageTimeoutSeconds());
        assertEquals("EXPERIMENTAL", profile.certification());
    }

    private static void assertRegionOwnedVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.4", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V8, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V5, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HINT_GENERIC_V1, profile.visualHintPackVersion());
        assertEquals(java.util.List.of(InferenceMode.IMAGE_ONLY), profile.supportedModes());
        assertEquals(0, profile.maximumRepairRounds());
        assertEquals(5, profile.maximumTotalCalls());
        assertEquals(240, profile.stageTimeoutSeconds());
        assertEquals("EXPERIMENTAL", profile.certification());
    }

    private static void assertDiagnosticHierarchyVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.5", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V8, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V6, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HINT_GENERIC_V1, profile.visualHintPackVersion());
        assertEquals(java.util.List.of(InferenceMode.IMAGE_ONLY), profile.supportedModes());
        assertEquals(0, profile.maximumRepairRounds());
        assertEquals(5, profile.maximumTotalCalls());
        assertEquals(240, profile.stageTimeoutSeconds());
        assertEquals("EXPERIMENTAL", profile.certification());
    }

    private static void assertSupportNormalizedHierarchyVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.6", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V8, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HINT_GENERIC_V1, profile.visualHintPackVersion());
        assertEquals(java.util.List.of(InferenceMode.IMAGE_ONLY), profile.supportedModes());
        assertEquals(0, profile.maximumRepairRounds());
        assertEquals(5, profile.maximumTotalCalls());
        assertEquals(240, profile.stageTimeoutSeconds());
        assertEquals("EXPERIMENTAL", profile.certification());
    }

    private static void assertRegionNormalizedHierarchyVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.7", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V8, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HINT_GENERIC_V1, profile.visualHintPackVersion());
        assertEquals(java.util.List.of(InferenceMode.IMAGE_ONLY), profile.supportedModes());
        assertEquals(0, profile.maximumRepairRounds());
        assertEquals(5, profile.maximumTotalCalls());
        assertEquals(240, profile.stageTimeoutSeconds());
        assertEquals("EXPERIMENTAL", profile.certification());
    }

    private static void assertConnectionNormalizedHierarchyVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.8", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V8, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HINT_GENERIC_V1, profile.visualHintPackVersion());
        assertEquals(java.util.List.of(InferenceMode.IMAGE_ONLY), profile.supportedModes());
        assertEquals(0, profile.maximumRepairRounds());
        assertEquals(5, profile.maximumTotalCalls());
        assertEquals(240, profile.stageTimeoutSeconds());
        assertEquals("EXPERIMENTAL", profile.certification());
    }

    private static void assertSupportOwnerNormalizedHierarchyVisualProfile(
            InferenceProfileRegistry registry,
            String profileId,
            String model
    ) {
        var profile = registry.require(profileId).profile();
        assertTrue(registry.isVisualGroundingProfile(profileId));
        assertFalse(registry.isProductLiveProfile(profileId));
        assertEquals(model, profile.model());
        assertEquals("renderweave-inference-pipeline/4.9", profile.pipelineVersion());
        assertEquals(InferencePromptRegistry.SCHEMA_CANDIDATE_V5, profile.promptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V8, profile.elementPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HIERARCHY_V7, profile.hierarchyPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_BINDINGS_V3, profile.bindingPromptVersion());
        assertEquals(InferencePromptRegistry.VISUAL_HINT_GENERIC_V1, profile.visualHintPackVersion());
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

    private static void assertHistoricalProductV4(
            InferenceProfileRegistry registry,
            String profileId,
            String model,
            long maximumCost
    ) {
        var profile = registry.require(profileId).profile();
        assertFalse(registry.isProductLiveProfile(profileId));
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
