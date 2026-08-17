package cn.hbads.renderweave.inference.certification;

import cn.hbads.renderweave.inference.profile.InferenceProfile;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOnlyV46ProfileTest {
    private static final String V45 = "dashscope-qwen38-max-product-v45-hybrid-generic";
    private static final String V46 = "dashscope-qwen38-max-product-v46-hybrid-generic";

    @Test
    void v46IsAnImmutableHiddenCandidateWithOnlyThreeSemanticDifferences() throws Exception {
        var registry = new InferenceProfileRegistry();
        var baseline = registry.require(V45);
        var candidate = registry.require(V46);

        assertEquals(Set.of("profileId", "maximumTotalCalls", "maximumEstimatedCostMicrosCny"),
                differingComponents(baseline.profile(), candidate.profile()));
        assertEquals(12, candidate.profile().maximumTotalCalls());
        assertEquals(6_000_000L, candidate.profile().maximumEstimatedCostMicrosCny());
        assertEquals(1, registry.certificationCandidateProfiles().size());
        assertEquals(V46, registry.certificationCandidateProfiles().getFirst().profile().profileId());
        assertTrue(registry.isCertificationCandidateProfile(V46));
        assertFalse(registry.isProductLiveProfile(V46));
        assertFalse(registry.productLiveProfiles().contains(candidate));
        assertEquals("EXPERIMENTAL", candidate.profile().certification());
        assertEquals("22f561c88b30fabbf3ba660bcfe203fb570975f770ff122f2ce1c7216454ac0c",
                candidate.canonicalSha256());
    }

    @Test
    void v46OwnsItsAggregateRunCapWhileHistoricalSnapshotsKeepLegacySemantics() {
        var registry = new InferenceProfileRegistry();
        var v45 = registry.require(V45).profile();
        var v46 = registry.require(V46).profile();

        assertEquals(6_000_000L, ProfileRunBudgetPolicy.effectiveRunCostLimit(v46, null));
        assertEquals(4_000_000L, ProfileRunBudgetPolicy.effectiveRunCostLimit(v46, 4_000_000L));
        assertNull(ProfileRunBudgetPolicy.effectiveRunCostLimit(v45, null));
        assertEquals(4_000_000L, ProfileRunBudgetPolicy.effectiveRunCostLimit(v45, 4_000_000L));
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
