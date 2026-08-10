package cn.hbads.renderweave.inference.live;

final class InvalidVisualAnalysisException extends RuntimeException {
    private final String diagnosticCode;

    InvalidVisualAnalysisException(String diagnosticCode, String message, Throwable cause) {
        super(message, cause);
        if (diagnosticCode == null || !diagnosticCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("diagnosticCode is invalid");
        }
        this.diagnosticCode = diagnosticCode;
    }

    String diagnosticCode() {
        return diagnosticCode;
    }
}

