package cn.hbads.renderweave.inference.certification;

import java.util.List;
import java.util.Map;

public record CertificationStageEvaluation(
        CertificationStage stage,
        int acceptedCases,
        int totalCases,
        boolean passed,
        String evidenceIdentity,
        Map<String, List<String>> flags
) {
    public CertificationStageEvaluation {
        flags = Map.copyOf(flags);
    }

    public CertificationStageOutcome toOutcome() {
        return new CertificationStageOutcome(stage, acceptedCases, totalCases, evidenceIdentity);
    }
}
