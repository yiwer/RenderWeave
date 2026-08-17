package cn.hbads.renderweave.inference.certification;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public record ProfileCertificationRecord(
        UUID cycleId,
        String profileId,
        String profileSha256,
        String manifestIdentity,
        String evaluatorIdentity,
        ProfileCertificationStatus status,
        Map<CertificationStage, Integer> acceptedCases,
        String productionPolicyAuthorityReference,
        String grantEvidenceIdentity,
        Instant grantedAt,
        Optional<Instant> revokedAt,
        Optional<String> revocationReason
) {
    public ProfileCertificationRecord {
        acceptedCases = Map.copyOf(acceptedCases);
        revokedAt = revokedAt == null ? Optional.empty() : revokedAt;
        revocationReason = revocationReason == null ? Optional.empty() : revocationReason;
    }
}
