package cn.hbads.renderweave.inference.candidate;

import java.util.UUID;

public record CandidateReference(
        CandidateReferenceKind kind,
        UUID candidateSchemaId,
        String schemaKey,
        String versionTag
) {
    public static CandidateReference candidate(UUID candidateSchemaId) {
        return new CandidateReference(CandidateReferenceKind.CANDIDATE_SCHEMA, candidateSchemaId, null, null);
    }

    public static CandidateReference draft(String schemaKey) {
        return new CandidateReference(CandidateReferenceKind.DRAFT, null, schemaKey, null);
    }

    public static CandidateReference staticSchema(String schemaKey, String versionTag) {
        return new CandidateReference(CandidateReferenceKind.STATIC, null, schemaKey, versionTag);
    }
}
