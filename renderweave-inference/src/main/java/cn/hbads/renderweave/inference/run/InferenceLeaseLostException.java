package cn.hbads.renderweave.inference.run;

import java.util.UUID;

public final class InferenceLeaseLostException extends RuntimeException {
    public InferenceLeaseLostException(UUID runId) {
        super("Inference lease is absent, expired, or owned by another worker: " + runId);
    }
}
