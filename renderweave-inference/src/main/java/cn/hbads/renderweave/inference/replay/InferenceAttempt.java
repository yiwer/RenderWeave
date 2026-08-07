package cn.hbads.renderweave.inference.replay;

import cn.hbads.renderweave.inference.run.InferenceStage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe attempt metadata: no prompt, input value, model output, or chain-of-thought is retained. */
public record InferenceAttempt(
        UUID runId,
        int attemptOrdinal,
        InferenceStage stage,
        InferenceAttemptStatus status,
        String outcomeCode,
        Instant completedAt
) {
    public InferenceAttempt {
        Objects.requireNonNull(runId, "runId");
        if (attemptOrdinal < 0 || attemptOrdinal > 2) {
            throw new IllegalArgumentException("attemptOrdinal must be 0..2");
        }
        Objects.requireNonNull(stage, "stage");
        if (stage != InferenceStage.STRUCTURE && stage != InferenceStage.REPAIR) {
            throw new IllegalArgumentException("Only STRUCTURE and REPAIR are replay call stages");
        }
        Objects.requireNonNull(status, "status");
        if (outcomeCode == null || !outcomeCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("outcomeCode must be a stable uppercase identifier");
        }
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
