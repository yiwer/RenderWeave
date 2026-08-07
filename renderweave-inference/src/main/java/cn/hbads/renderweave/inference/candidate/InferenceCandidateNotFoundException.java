package cn.hbads.renderweave.inference.candidate;

import java.util.UUID;

public final class InferenceCandidateNotFoundException extends RuntimeException {
    private final UUID runId;

    public InferenceCandidateNotFoundException(UUID runId) {
        super("Inference candidate not found: " + runId);
        this.runId = runId;
    }

    public UUID runId() {
        return runId;
    }
}
