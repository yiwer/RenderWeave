package cn.hbads.renderweave.inference.provider;

import cn.hbads.renderweave.inference.profile.InferenceProfile;

import java.util.Objects;

/** Resolves the run aggregate cap without changing historical Profile snapshot semantics. */
public final class ProfileRunBudgetPolicy {
    public static final String IMAGE_ONLY_V46_PROFILE_ID =
            "dashscope-qwen38-max-product-v46-hybrid-generic";
    public static final String IMAGE_ONLY_V47_PROFILE_ID =
            "dashscope-qwen38-max-product-v47-hybrid-generic";
    public static final String IMAGE_ONLY_V48_PROFILE_ID =
            "dashscope-qwen38-max-product-v48-hybrid-generic";
    public static final String IMAGE_ONLY_V49_PROFILE_ID =
            "dashscope-qwen38-max-product-v49-hybrid-generic";
    public static final String IMAGE_ONLY_V50_PROFILE_ID =
            "dashscope-qwen38-max-product-v50-hybrid-generic";
    public static final String IMAGE_ONLY_V51_PROFILE_ID =
            "dashscope-qwen38-max-product-v51-hybrid-generic";
    public static final String IMAGE_ONLY_V52_PROFILE_ID =
            "dashscope-qwen38-max-product-v52-hybrid-generic";

    private ProfileRunBudgetPolicy() { }

    public static Long effectiveRunCostLimit(InferenceProfile profile, Long clientLimitMicrosCny) {
        Objects.requireNonNull(profile, "profile");
        if (clientLimitMicrosCny != null && clientLimitMicrosCny < 1) {
            throw new IllegalArgumentException("Client run cost limit must be positive");
        }
        if (!isImageOnlyCertificationProfile(profile.profileId())) return clientLimitMicrosCny;
        var profileLimit = profile.maximumEstimatedCostMicrosCny();
        return clientLimitMicrosCny == null ? profileLimit : Math.min(profileLimit, clientLimitMicrosCny);
    }

    public static boolean isImageOnlyCertificationProfile(String profileId) {
        return IMAGE_ONLY_V46_PROFILE_ID.equals(profileId)
                || IMAGE_ONLY_V47_PROFILE_ID.equals(profileId)
                || IMAGE_ONLY_V48_PROFILE_ID.equals(profileId)
                || IMAGE_ONLY_V49_PROFILE_ID.equals(profileId)
                || IMAGE_ONLY_V50_PROFILE_ID.equals(profileId)
                || IMAGE_ONLY_V51_PROFILE_ID.equals(profileId)
                || IMAGE_ONLY_V52_PROFILE_ID.equals(profileId);
    }
}
