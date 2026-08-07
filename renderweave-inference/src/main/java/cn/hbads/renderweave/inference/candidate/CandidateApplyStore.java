package cn.hbads.renderweave.inference.candidate;

import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Narrow create-only persistence capability exposed to inference; it has no update, publish or delete operation. */
public interface CandidateApplyStore {
    PersistenceResult apply(
            UUID runId,
            long expectedCandidateRevision,
            String finalCandidateJson,
            MaterializedDraftBundle bundle,
            Instant now
    );

    record PersistenceResult(InferenceRunSnapshot run, Instant appliedAt) {
        public PersistenceResult {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(appliedAt, "appliedAt");
        }
    }
}
