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

class ImageOnlyV51ProfileTest {
    private static final String V50 = ProfileRunBudgetPolicy.IMAGE_ONLY_V50_PROFILE_ID;
    private static final String V51 = ProfileRunBudgetPolicy.IMAGE_ONLY_V51_PROFILE_ID;
    private static final String V50_SHA =
            "62f333aee7096f09d6d04dea004641e8b0a9c425ee133d09a563594d81200691";
    private static final String V51_SHA =
            "972001414977a7cc788def6e8e106b2c7f146a306d1fa328d48ff053d472d3bd";

    @Test
    void provenanceSuccessorChangesOnlyExactIdentityAndPipeline() throws Exception {
        var registry = new InferenceProfileRegistry();
        var failed = registry.require(V50);
        var successor = registry.require(V51);

        assertEquals(V50_SHA, failed.canonicalSha256());
        assertEquals(Set.of("profileId", "pipelineVersion"),
                differingComponents(failed.profile(), successor.profile()));
        assertEquals("renderweave-inference-pipeline/4.33",
                successor.profile().pipelineVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V16,
                successor.profile().elementPromptVersion());
        assertEquals(V51_SHA, successor.canonicalSha256());
        assertTrue(registry.isCertificationCandidateProfile(V51));
        assertTrue(ProfileRunBudgetPolicy.isImageOnlyCertificationProfile(V51));
        assertFalse(registry.isProductLiveProfile(V51));
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
