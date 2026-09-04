package cn.hbads.renderweave.inference.retention;

public final class PayloadLifecycleException extends RuntimeException {
    private final String code;

    public PayloadLifecycleException(String code, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("Payload lifecycle code is invalid");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
