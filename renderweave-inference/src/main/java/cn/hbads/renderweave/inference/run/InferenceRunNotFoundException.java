package cn.hbads.renderweave.inference.run;

import java.util.UUID;

public final class InferenceRunNotFoundException extends RuntimeException {
    public InferenceRunNotFoundException(UUID runId) {
        super("Inference run not found: " + runId);
    }
}
