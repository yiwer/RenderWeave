package cn.hbads.renderweave.inference.certification;

import java.util.Objects;

public record CertificationCaseAssignment(
        int rank,
        String caseId,
        String caseSha256,
        String caseIdentity,
        CertificationCaseRole role
) {
    public CertificationCaseAssignment {
        if (rank < 0 || rank >= 60) throw new IllegalArgumentException("CERTIFICATION_CASE_RANK_INVALID");
        CertificationCanaryCase.requireCaseId(caseId);
        CertificationCanaryCase.requireSha(caseSha256);
        if (caseIdentity == null || !caseIdentity.matches(
                "renderweave-layered-case/2\\.0:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("CERTIFICATION_CASE_IDENTITY_INVALID");
        }
        Objects.requireNonNull(role, "role");
    }
}
