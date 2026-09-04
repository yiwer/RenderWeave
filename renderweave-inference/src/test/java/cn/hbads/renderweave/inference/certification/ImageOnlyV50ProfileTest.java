package cn.hbads.renderweave.inference.certification;

import cn.hbads.renderweave.inference.profile.InferenceProfile;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.profile.InferencePromptRegistry;
import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOnlyV50ProfileTest {
    private static final String V49 = ProfileRunBudgetPolicy.IMAGE_ONLY_V49_PROFILE_ID;
    private static final String V50 = ProfileRunBudgetPolicy.IMAGE_ONLY_V50_PROFILE_ID;
    private static final String V49_SHA =
            "acffdd4dd56ca2f1f7260fc5d37aa48ca3da488a0ae2718f2095bf1530e86eaf";
    private static final String V50_SHA =
            "62f333aee7096f09d6d04dea004641e8b0a9c425ee133d09a563594d81200691";

    @Test
    void localIdCanonicalizationSuccessorChangesOnlyExactIdentityPromptAndPipeline()
            throws Exception {
        var registry = new InferenceProfileRegistry();
        var failed = registry.require(V49);
        var successor = registry.require(V50);

        assertEquals(V49_SHA, failed.canonicalSha256());
        assertEquals(Set.of("profileId", "pipelineVersion", "elementPromptVersion"),
                differingComponents(failed.profile(), successor.profile()));
        assertEquals("renderweave-inference-pipeline/4.32",
                successor.profile().pipelineVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V16,
                successor.profile().elementPromptVersion());
        assertEquals(V50_SHA, successor.canonicalSha256());
        assertTrue(registry.isCertificationCandidateProfile(V50));
        assertTrue(ProfileRunBudgetPolicy.isImageOnlyCertificationProfile(V50));
        assertFalse(registry.isProductLiveProfile(V50));
        assertEquals("EXPERIMENTAL", successor.profile().certification());
        assertEquals(12, successor.profile().maximumTotalCalls());
        assertEquals(8_192, successor.profile().maximumOutputTokens());
        assertEquals(360, successor.profile().stageTimeoutSeconds());
        assertEquals(6_000_000L, ProfileRunBudgetPolicy.effectiveRunCostLimit(
                successor.profile(), null));
    }

    private static Set<String> differingComponents(InferenceProfile left, InferenceProfile right)
            throws Exception {
        var result = new HashSet<String>();
        for (RecordComponent component : InferenceProfile.class.getRecordComponents()) {
            var leftValue = component.getAccessor().invoke(left);
            var rightValue = component.getAccessor().invoke(right);
            if (!java.util.Objects.equals(leftValue, rightValue)) result.add(component.getName());
        }
        return Set.copyOf(result);
    }
}
