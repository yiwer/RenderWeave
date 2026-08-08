package cn.hbads.renderweave.inference;

final class LiveInferenceUnavailableException extends RuntimeException {
    private final String code;

    LiveInferenceUnavailableException(String code, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("Live inference problem code is invalid");
        }
        this.code = code;
    }

    String code() {
        return code;
    }
}
