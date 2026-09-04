package cn.hbads.renderweave.inference.certification;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ProfileCertificationRecord(
        UUID cycleId,
        String profileId,
        String profileSha256,
        String manifestIdentity,
        String evaluatorIdentity,
        String authorityInventorySha256,
        ProfileCertificationStatus status,
        Map<CertificationStage, Integer> acceptedCases,
        Map<CertificationStage, Integer> acceptanceThresholds,
        Map<CertificationStage, String> stageEvidenceIdentities,
        String productionPolicyAuthorityReference,
        String grantEvidenceIdentity,
        Instant grantedAt,
        Optional<Instant> revokedAt,
        Optional<String> revocationReason
) {
    public ProfileCertificationRecord {
        Objects.requireNonNull(grantedAt, "grantedAt");
        new FrozenCertificationCycle(cycleId, profileId, profileSha256, manifestIdentity,
                evaluatorIdentity, authorityInventorySha256, grantedAt);
        if (status != ProfileCertificationStatus.GRANTED
                && status != ProfileCertificationStatus.REVOKED) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_RECORD_STATUS_INVALID");
        }
        acceptedCases = Map.copyOf(acceptedCases);
        acceptanceThresholds = Map.copyOf(acceptanceThresholds);
        stageEvidenceIdentities = Map.copyOf(stageEvidenceIdentities);
        var stages = java.util.Set.copyOf(Arrays.asList(CertificationStage.scoredStages()));
        if (!acceptedCases.keySet().equals(stages)
                || !acceptanceThresholds.keySet().equals(stages)
                || !stageEvidenceIdentities.keySet().equals(stages)) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_RECORD_STAGE_SET_INVALID");
        }
        for (var stage : CertificationStage.scoredStages()) {
            if (!acceptanceThresholds.get(stage).equals(stage.acceptanceThreshold())
                    || acceptedCases.get(stage) < stage.acceptanceThreshold()
                    || acceptedCases.get(stage) > stage.caseCount()
                    || !stageEvidenceIdentities.get(stage).matches(
                    "renderweave-image-only-certification-stage-evidence/1\\.0:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("PROFILE_CERTIFICATION_RECORD_STAGE_PROOF_INVALID");
            }
        }
        if (productionPolicyAuthorityReference == null
                || !productionPolicyAuthorityReference.matches(
                "production-policy-j1:[a-z0-9][a-z0-9-]{2,95}")) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_RECORD_J1_INVALID");
        }
        if (grantEvidenceIdentity == null || !grantEvidenceIdentity.matches(
                "renderweave-image-only-certification-grant-evidence/1\\.0:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_RECORD_GRANT_EVIDENCE_INVALID");
        }
        revokedAt = revokedAt == null ? Optional.empty() : revokedAt;
        revocationReason = revocationReason == null ? Optional.empty() : revocationReason;
        if ((status == ProfileCertificationStatus.REVOKED)
                != (revokedAt.isPresent() && revocationReason.isPresent())) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_RECORD_REVOCATION_INVALID");
        }
        if (revokedAt.filter(value -> value.isBefore(grantedAt)).isPresent()) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_RECORD_REVOCATION_TIME_INVALID");
        }
    }
}
