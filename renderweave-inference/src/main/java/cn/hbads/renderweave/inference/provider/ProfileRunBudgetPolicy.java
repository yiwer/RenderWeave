package cn.hbads.renderweave.inference.provider;

import cn.hbads.renderweave.inference.profile.InferenceProfile;

import java.util.Objects;

/** Resolves the run aggregate cap without changing historical Profile snapshot semantics. */
public final class ProfileRunBudgetPolicy {
    public static final String IMAGE_ONLY_V46_PROFILE_ID =
            "dashscope-qwen38-max-product-v46-hybrid-generic";

    private ProfileRunBudgetPolicy() { }

    public static Long effectiveRunCostLimit(InferenceProfile profile, Long clientLimitMicrosCny) {
        Objects.requireNonNull(profile, "profile");
        if (clientLimitMicrosCny != null && clientLimitMicrosCny < 1) {
            throw new IllegalArgumentException("Client run cost limit must be positive");
        }
        if (!IMAGE_ONLY_V46_PROFILE_ID.equals(profile.profileId())) return clientLimitMicrosCny;
        var profileLimit = profile.maximumEstimatedCostMicrosCny();
        return clientLimitMicrosCny == null ? profileLimit : Math.min(profileLimit, clientLimitMicrosCny);
    }
}
