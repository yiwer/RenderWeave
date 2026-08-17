package cn.hbads.renderweave.inference.certification;

public record CertificationStageCase(String caseId, String caseSha256, String caseIdentity) {
    public CertificationStageCase {
        CertificationCanaryCase.requireCaseId(caseId);
        CertificationCanaryCase.requireSha(caseSha256);
        if (caseIdentity == null || !caseIdentity.matches("[a-z0-9][a-z0-9._/-]{2,190}:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("CERTIFICATION_STAGE_CASE_IDENTITY_INVALID");
        }
    }
}
