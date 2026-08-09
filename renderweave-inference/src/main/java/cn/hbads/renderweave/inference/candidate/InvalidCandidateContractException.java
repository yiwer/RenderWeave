package cn.hbads.renderweave.inference.candidate;

public final class InvalidCandidateContractException extends IllegalArgumentException {
    private final String code;
    private final String diagnosticCode;

    public InvalidCandidateContractException(String code, String message, Throwable cause) {
        this(code, code, message, cause);
    }

    public InvalidCandidateContractException(
            String code,
            String diagnosticCode,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("code must be a stable uppercase identifier");
        }
        if (diagnosticCode == null || !diagnosticCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("diagnosticCode must be a stable uppercase identifier");
        }
        this.code = code;
        this.diagnosticCode = diagnosticCode;
    }

    public String code() {
        return code;
    }

    /** Stable low-cardinality reason; never contains provider members, values, paths or messages. */
    public String diagnosticCode() {
        return diagnosticCode;
    }
}
