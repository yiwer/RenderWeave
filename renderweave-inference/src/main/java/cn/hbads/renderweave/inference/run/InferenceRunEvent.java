package cn.hbads.renderweave.inference.run;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InferenceRunEvent(
        UUID runId,
        long sequence,
        String type,
        InferenceRunState state,
        InferenceStage stage,
        String dataJson,
        Instant occurredAt
) {
    public InferenceRunEvent {
        Objects.requireNonNull(runId, "runId");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type is required");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(stage, "stage");
        if (dataJson == null || dataJson.isBlank()) throw new IllegalArgumentException("dataJson is required");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
