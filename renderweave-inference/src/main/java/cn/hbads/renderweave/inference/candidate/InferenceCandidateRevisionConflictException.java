package cn.hbads.renderweave.inference.candidate;

import java.util.UUID;

public final class InferenceCandidateRevisionConflictException extends RuntimeException {
    private final UUID runId;
    private final long expectedRevision;
    private final long currentRevision;

    public InferenceCandidateRevisionConflictException(
            UUID runId,
            long expectedRevision,
            long currentRevision
    ) {
        super("Candidate revision conflict for " + runId);
        this.runId = runId;
        this.expectedRevision = expectedRevision;
        this.currentRevision = currentRevision;
    }

    public UUID runId() {
        return runId;
    }

    public long expectedRevision() {
        return expectedRevision;
    }

    public long currentRevision() {
        return currentRevision;
    }
}
