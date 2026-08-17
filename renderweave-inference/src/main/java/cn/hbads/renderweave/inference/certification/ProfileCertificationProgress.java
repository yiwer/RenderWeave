package cn.hbads.renderweave.inference.certification;

import java.util.Objects;
import java.util.UUID;

/** Store-derived progress; callers outside this module cannot forge a stage unlock. */
public final class ProfileCertificationProgress {
    private final UUID cycleId;
    private final ProfileCertificationStatus status;
    private final CertificationStage nextStage;
    private final String profileSha256;
    private final String manifestIdentity;
    private final String evaluatorIdentity;
    private final String authorityInventorySha256;

    ProfileCertificationProgress(
            UUID cycleId,
            ProfileCertificationStatus status,
            CertificationStage nextStage,
            String profileSha256,
            String manifestIdentity,
            String evaluatorIdentity,
            String authorityInventorySha256
    ) {
        this.cycleId = Objects.requireNonNull(cycleId, "cycleId");
        this.status = Objects.requireNonNull(status, "status");
        if ((status == ProfileCertificationStatus.IN_PROGRESS) != (nextStage != null)) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_PROGRESS_SHAPE_INVALID");
        }
        this.nextStage = nextStage;
        this.profileSha256 = requireIdentity(profileSha256, "PROFILE_CERTIFICATION_PROGRESS_PROFILE_INVALID");
        this.manifestIdentity = requireIdentity(manifestIdentity,
                "PROFILE_CERTIFICATION_PROGRESS_MANIFEST_INVALID");
        this.evaluatorIdentity = requireIdentity(evaluatorIdentity,
                "PROFILE_CERTIFICATION_PROGRESS_EVALUATOR_INVALID");
        this.authorityInventorySha256 = requireIdentity(authorityInventorySha256,
                "PROFILE_CERTIFICATION_PROGRESS_INVENTORY_INVALID");
    }

    public UUID cycleId() {
        return cycleId;
    }

    public ProfileCertificationStatus status() {
        return status;
    }

    public CertificationStage nextStage() {
        return nextStage;
    }

    public String profileSha256() {
        return profileSha256;
    }

    public String manifestIdentity() {
        return manifestIdentity;
    }

    public String evaluatorIdentity() {
        return evaluatorIdentity;
    }

    public String authorityInventorySha256() {
        return authorityInventorySha256;
    }

    private static String requireIdentity(String value, String code) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(code);
        return value;
    }
}
