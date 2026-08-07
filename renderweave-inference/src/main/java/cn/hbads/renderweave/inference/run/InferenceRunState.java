package cn.hbads.renderweave.inference.run;

public enum InferenceRunState {
    QUEUED,
    RUNNING,
    REVIEW_REQUIRED,
    APPLYING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
