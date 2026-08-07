package cn.hbads.renderweave.inference.run;

import java.util.UUID;

public final class InvalidInferenceRunTransitionException extends RuntimeException {
    public InvalidInferenceRunTransitionException(UUID runId, String message) {
        super("Inference run " + runId + ": " + message);
    }
}
