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

class ImageOnlyV48ProfileTest {
    private static final String V47 = ProfileRunBudgetPolicy.IMAGE_ONLY_V47_PROFILE_ID;
    private static final String V48 = ProfileRunBudgetPolicy.IMAGE_ONLY_V48_PROFILE_ID;
    private static final String V48_SHA =
            "22f40ef4c865e11778eef4558c20c383e6611e068d8d08be0d080650074d4470";

    @Test
    void regionRecoverySuccessorChangesOnlyItsExactIdentityPromptAndPipeline() throws Exception {
        var registry = new InferenceProfileRegistry();
        var failed = registry.require(V47);
        var successor = registry.require(V48);

        assertEquals(Set.of("profileId", "pipelineVersion", "elementPromptVersion"),
                differingComponents(failed.profile(), successor.profile()));
        assertEquals("renderweave-inference-pipeline/4.30",
                successor.profile().pipelineVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V14,
                successor.profile().elementPromptVersion());
        assertEquals(V48_SHA, successor.canonicalSha256());
        assertTrue(registry.isCertificationCandidateProfile(V48));
        assertFalse(registry.isProductLiveProfile(V48));
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
