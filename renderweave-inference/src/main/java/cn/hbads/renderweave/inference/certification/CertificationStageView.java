package cn.hbads.renderweave.inference.certification;

import java.util.List;
import java.util.Objects;

public record CertificationStageView(
        CertificationStage stage,
        int acceptanceThreshold,
        List<CertificationStageCase> cases
) {
    public CertificationStageView {
        Objects.requireNonNull(stage, "stage");
        if (acceptanceThreshold != stage.acceptanceThreshold()) {
            throw new IllegalArgumentException("CERTIFICATION_STAGE_THRESHOLD_DRIFT");
        }
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        if (cases.size() != stage.caseCount()) {
            throw new IllegalArgumentException("CERTIFICATION_STAGE_CASE_COUNT_DRIFT");
        }
    }
}
