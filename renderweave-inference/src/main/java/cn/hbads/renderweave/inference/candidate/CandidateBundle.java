package cn.hbads.renderweave.inference.candidate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CandidateBundle(
        String contractVersion,
        UUID rootCandidateSchemaId,
        List<CandidateSchema> schemas
) {
    public static final String CONTRACT_VERSION = "renderweave-candidate/1.0";

    public CandidateBundle {
        Objects.requireNonNull(contractVersion, "contractVersion");
        Objects.requireNonNull(rootCandidateSchemaId, "rootCandidateSchemaId");
        schemas = List.copyOf(Objects.requireNonNull(schemas, "schemas"));
    }
}
