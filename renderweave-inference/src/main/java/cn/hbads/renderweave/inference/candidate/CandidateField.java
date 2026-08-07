package cn.hbads.renderweave.inference.candidate;

import java.util.Objects;
import java.util.UUID;

public record CandidateField(
        UUID candidateFieldId,
        String proposedFieldKey,
        String displayName,
        boolean required,
        CandidateValue value,
        CandidateSource source,
        CandidateAssessment assessment
) {
    public CandidateField {
        Objects.requireNonNull(candidateFieldId, "candidateFieldId");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(assessment, "assessment");
    }
}
