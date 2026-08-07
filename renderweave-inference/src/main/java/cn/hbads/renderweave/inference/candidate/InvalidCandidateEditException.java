package cn.hbads.renderweave.inference.candidate;

public final class InvalidCandidateEditException extends RuntimeException {
    private final String code;

    public InvalidCandidateEditException(String code, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("code must be a stable uppercase identifier");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
