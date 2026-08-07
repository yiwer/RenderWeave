package cn.hbads.renderweave.inference.candidate;

import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;
import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CandidateApplyResult(
        InferenceRunSnapshot run,
        long candidateRevision,
        CandidateBundle finalCandidate,
        SchemaKey rootSchemaKey,
        List<SchemaKey> createdSchemaKeys,
        Instant appliedAt
) {
    public CandidateApplyResult {
        Objects.requireNonNull(run, "run");
        if (candidateRevision < 0) throw new IllegalArgumentException("candidateRevision must not be negative");
        Objects.requireNonNull(finalCandidate, "finalCandidate");
        Objects.requireNonNull(rootSchemaKey, "rootSchemaKey");
        createdSchemaKeys = List.copyOf(Objects.requireNonNull(createdSchemaKeys, "createdSchemaKeys"));
        Objects.requireNonNull(appliedAt, "appliedAt");
    }
}
