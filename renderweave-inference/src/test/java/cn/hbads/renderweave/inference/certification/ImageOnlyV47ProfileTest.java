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

class ImageOnlyV47ProfileTest {
    private static final String V46 = ProfileRunBudgetPolicy.IMAGE_ONLY_V46_PROFILE_ID;
    private static final String V47 = ProfileRunBudgetPolicy.IMAGE_ONLY_V47_PROFILE_ID;
    private static final String V47_SHA =
            "a9fe98e1cfa4b7cc126db1f74601fdebe60526a1c999924daf189ed5f1ac5eb0";

    @Test
    void successorChangesOnlyItsExactIdentityPromptAndPipeline() throws Exception {
        var registry = new InferenceProfileRegistry();
        var failed = registry.require(V46);
        var successor = registry.require(V47);

        assertEquals(Set.of("profileId", "pipelineVersion", "elementPromptVersion"),
                differingComponents(failed.profile(), successor.profile()));
        assertEquals("renderweave-inference-pipeline/4.29",
                successor.profile().pipelineVersion());
        assertEquals(InferencePromptRegistry.VISUAL_ELEMENTS_V13,
                successor.profile().elementPromptVersion());
        assertEquals(V47_SHA, successor.canonicalSha256());
        assertTrue(registry.isCertificationCandidateProfile(V47));
        assertFalse(registry.isCertificationCandidateProfile(V46));
        assertFalse(registry.isProductLiveProfile(V47));
        assertEquals(12, successor.profile().maximumTotalCalls());
        assertEquals(8_192, successor.profile().maximumOutputTokens());
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
