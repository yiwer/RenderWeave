package cn.hbads.renderweave.inference.run;

public final class InferenceIdempotencyConflictException extends RuntimeException {
    private final String idempotencyKey;

    public InferenceIdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key was already used for another inference request");
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
