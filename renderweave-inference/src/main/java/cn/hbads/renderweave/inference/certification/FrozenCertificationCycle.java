package cn.hbads.renderweave.inference.certification;

import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FrozenCertificationCycle(
        UUID cycleId,
        String profileId,
        String profileSha256,
        String manifestIdentity,
        String evaluatorIdentity,
        String authorityInventorySha256,
        Instant createdAt
) {
    public FrozenCertificationCycle {
        Objects.requireNonNull(cycleId, "cycleId");
        if (!ProfileRunBudgetPolicy.IMAGE_ONLY_V46_PROFILE_ID.equals(profileId)) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_PROFILE_INVALID");
        }
        requireSha256(profileSha256, "PROFILE_CERTIFICATION_PROFILE_SHA_INVALID");
        requireVersionedDigest(manifestIdentity, "PROFILE_CERTIFICATION_MANIFEST_IDENTITY_INVALID");
        requireVersionedDigest(evaluatorIdentity, "PROFILE_CERTIFICATION_EVALUATOR_IDENTITY_INVALID");
        requireSha256(authorityInventorySha256,
                "PROFILE_CERTIFICATION_AUTHORITY_INVENTORY_SHA_INVALID");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static void requireSha256(String value, String code) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(code);
    }

    private static void requireVersionedDigest(String value, String code) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9._/-]{2,190}/[0-9]+\\.[0-9]+:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(code);
        }
    }
}
