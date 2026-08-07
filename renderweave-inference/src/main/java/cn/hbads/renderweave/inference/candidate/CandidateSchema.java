package cn.hbads.renderweave.inference.candidate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CandidateSchema(
        UUID candidateSchemaId,
        String proposedSchemaKey,
        String displayName,
        CandidateSource source,
        CandidateAssessment assessment,
        List<CandidateField> fields
) {
    public CandidateSchema {
        Objects.requireNonNull(candidateSchemaId, "candidateSchemaId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(assessment, "assessment");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }
}
