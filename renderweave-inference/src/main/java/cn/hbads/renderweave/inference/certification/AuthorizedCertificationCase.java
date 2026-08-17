package cn.hbads.renderweave.inference.certification;

public record AuthorizedCertificationCase(String caseId, String artifactSha256) {
    public AuthorizedCertificationCase {
        CertificationCanaryCase.requireCaseId(caseId);
        CertificationCanaryCase.requireSha(artifactSha256);
    }
}
