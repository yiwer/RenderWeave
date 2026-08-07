package cn.hbads.renderweave.inference.candidate;

/** Stable failure raised before any Candidate content crosses into the formal Draft repository. */
public final class CandidateMaterializationException extends IllegalArgumentException {
    private final String code;

    public CandidateMaterializationException(String code, String message) {
        this(code, message, null);
    }

    public CandidateMaterializationException(String code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("code must be a stable uppercase identifier");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
