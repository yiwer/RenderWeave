package cn.hbads.renderweave.inference.certification;

import java.util.Objects;

public record CertificationStageOutcome(
        CertificationStage stage,
        int acceptedCases,
        int totalCases,
        String evidenceIdentity
) {
    public CertificationStageOutcome {
        Objects.requireNonNull(stage, "stage");
        if (totalCases != stage.caseCount() || acceptedCases < 0 || acceptedCases > totalCases) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_STAGE_COUNTS_INVALID");
        }
        if (evidenceIdentity == null || !evidenceIdentity.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{2,255}")) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_EVIDENCE_IDENTITY_INVALID");
        }
    }

    public boolean passed() {
        return acceptedCases >= stage.acceptanceThreshold();
    }
}
