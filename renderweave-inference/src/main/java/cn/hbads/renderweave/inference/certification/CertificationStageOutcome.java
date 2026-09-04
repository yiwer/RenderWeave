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
        if (!stage.scored()) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_STAGE_NOT_SCORING");
        }
        if (totalCases != stage.caseCount() || acceptedCases < 0 || acceptedCases > totalCases) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_STAGE_COUNTS_INVALID");
        }
        if (evidenceIdentity == null || !evidenceIdentity.matches(
                "renderweave-image-only-certification-stage-evidence/1\\.0:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("PROFILE_CERTIFICATION_EVIDENCE_IDENTITY_INVALID");
        }
    }

    public boolean passed() {
        return acceptedCases >= stage.acceptanceThreshold();
    }
}
