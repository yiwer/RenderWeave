package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.replay.InferenceRejectionEnvelope;
import cn.hbads.renderweave.inference.run.InferenceStage;

import java.util.Objects;
import java.util.Optional;

final class InvalidVisualAnalysisException extends RuntimeException {
    private final String diagnosticCode;
    private final InferenceStage earliestStage;
    private final Optional<InferenceRejectionEnvelope> rejectionEnvelope;

    InvalidVisualAnalysisException(String diagnosticCode, String message, Throwable cause) {
        this(diagnosticCode, message, cause, null);
    }

    InvalidVisualAnalysisException(
            String diagnosticCode,
            String message,
            Throwable cause,
            InferenceStage earliestStage
    ) {
        this(diagnosticCode, message, cause, earliestStage, Optional.empty());
    }

    InvalidVisualAnalysisException(
            InferenceRejectionEnvelope rejectionEnvelope,
            String message,
            Throwable cause
    ) {
        this(
                Objects.requireNonNull(rejectionEnvelope, "rejectionEnvelope").primaryCode(),
                message, cause, rejectionEnvelope.earliestStage(), Optional.of(rejectionEnvelope)
        );
    }

    private InvalidVisualAnalysisException(
            String diagnosticCode,
            String message,
            Throwable cause,
            InferenceStage earliestStage,
            Optional<InferenceRejectionEnvelope> rejectionEnvelope
    ) {
        super(message, cause);
        if (diagnosticCode == null || !diagnosticCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("diagnosticCode is invalid");
        }
        this.diagnosticCode = diagnosticCode;
        this.earliestStage = earliestStage;
        this.rejectionEnvelope = Objects.requireNonNull(
                rejectionEnvelope, "rejectionEnvelope"
        );
    }

    String diagnosticCode() {
        return diagnosticCode;
    }

    Optional<InferenceStage> earliestStage() {
        return Optional.ofNullable(earliestStage);
    }

    Optional<InferenceRejectionEnvelope> rejectionEnvelope() {
        return rejectionEnvelope;
    }
}

