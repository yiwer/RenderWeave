package cn.hbads.renderweave.inference.input;

public final class InferenceStorageException extends RuntimeException {
    private final String code;

    public InferenceStorageException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
