package cn.hbads.renderweave.inference.candidate;

import java.util.List;
import java.util.Objects;

public record CandidateAssessment(
        Integer confidenceBps,
        boolean inferred,
        CandidateResolution resolution,
        List<CandidateEvidence> evidence
) {
    public CandidateAssessment {
        Objects.requireNonNull(resolution, "resolution");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }

    public static CandidateAssessment ai(
            int confidenceBps,
            boolean inferred,
            CandidateResolution resolution,
            List<CandidateEvidence> evidence
    ) {
        return new CandidateAssessment(confidenceBps, inferred, resolution, evidence);
    }

    public static CandidateAssessment user() {
        return new CandidateAssessment(null, false, CandidateResolution.NOT_REQUIRED, List.of());
    }
}
