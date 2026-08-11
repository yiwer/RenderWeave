package cn.hbads.renderweave.inference.vision;

/** Stable, payload-free failure emitted by a local document vision adapter. */
public final class DocumentVisionException extends RuntimeException {
    private final String code;

    public DocumentVisionException(String code) {
        super(requireCode(code));
        this.code = code;
    }

    public String code() {
        return code;
    }

    private static String requireCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("Document vision failure code is invalid");
        }
        return value;
    }
}
