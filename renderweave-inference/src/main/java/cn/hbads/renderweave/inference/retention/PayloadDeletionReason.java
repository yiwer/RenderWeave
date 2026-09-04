package cn.hbads.renderweave.inference.retention;

public enum PayloadDeletionReason {
    COMPLETED,
    TERMINAL_RETENTION_ELAPSED,
    PAYLOAD_EXPIRED,
    USER_REQUESTED
}
