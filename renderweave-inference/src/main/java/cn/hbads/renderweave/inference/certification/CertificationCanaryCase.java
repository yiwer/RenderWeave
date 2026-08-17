package cn.hbads.renderweave.inference.certification;

public record CertificationCanaryCase(String caseId, String artifactSha256) {
    public CertificationCanaryCase {
        requireCaseId(caseId);
        requireSha(artifactSha256);
    }

    static void requireCaseId(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{2,95}")) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_CASE_ID_INVALID");
        }
    }

    static void requireSha(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_CASE_SHA_INVALID");
        }
    }
}
