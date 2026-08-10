package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.run.InferenceStage;

import java.util.Optional;

final class InvalidVisualAnalysisException extends RuntimeException {
    private final String diagnosticCode;
    private final InferenceStage earliestStage;

    InvalidVisualAnalysisException(String diagnosticCode, String message, Throwable cause) {
        this(diagnosticCode, message, cause, null);
    }

    InvalidVisualAnalysisException(
            String diagnosticCode,
            String message,
            Throwable cause,
            InferenceStage earliestStage
    ) {
        super(message, cause);
        if (diagnosticCode == null || !diagnosticCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("diagnosticCode is invalid");
        }
        this.diagnosticCode = diagnosticCode;
        this.earliestStage = earliestStage;
    }

    String diagnosticCode() {
        return diagnosticCode;
    }

    Optional<InferenceStage> earliestStage() {
        return Optional.ofNullable(earliestStage);
    }
}

