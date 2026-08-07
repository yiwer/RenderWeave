package cn.hbads.renderweave.inference.candidate;

public record CandidateEvidence(
        CandidateEvidenceKind kind,
        String artifactId,
        CandidateBoundingBox boundingBox,
        Integer sampleIndex,
        String jsonPointer
) {
    public static CandidateEvidence image(String artifactId, CandidateBoundingBox boundingBox) {
        return new CandidateEvidence(CandidateEvidenceKind.IMAGE, artifactId, boundingBox, null, null);
    }

    public static CandidateEvidence json(int sampleIndex, String jsonPointer) {
        return new CandidateEvidence(CandidateEvidenceKind.JSON, null, null, sampleIndex, jsonPointer);
    }
}
