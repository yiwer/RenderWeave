package cn.hbads.renderweave.inference.admission;

import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;

import java.util.Objects;

/** Exact server-owned policy, contract, notice and Profile snapshot for one admitted route. */
public record LiveAdmissionConfiguration(
        ExternalTransferNotice notice,
        InferenceProfileRegistry.ProfileResource profile
) {
    public LiveAdmissionConfiguration {
        Objects.requireNonNull(notice, "notice");
        Objects.requireNonNull(profile, "profile");
        var value = profile.profile();
        if (!value.networkAllowed() || !value.supportedModes().contains(InferenceMode.IMAGE_ONLY)) {
            throw new IllegalArgumentException("Live admission Profile must allow IMAGE_ONLY network inference");
        }
        if (!notice.profileId().equals(value.profileId())
                || !notice.profileSha256().equals(profile.canonicalSha256())
                || !notice.provider().equals(value.provider())
                || !notice.model().equals(value.model())
                || !notice.endpoint().equals(value.providerEndpoint())
                || notice.maximumProviderCalls() != value.maximumTotalCalls()
                || notice.maximumCostMicrosCny() != value.maximumEstimatedCostMicrosCny()) {
            throw new IllegalArgumentException("Notice must bind the exact immutable Profile route and caps");
        }
    }
}
