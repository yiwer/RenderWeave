package cn.hbads.renderweave.inference.run;

import cn.hbads.renderweave.inference.input.InferenceMode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record InferenceRunSnapshot(
        UUID runId,
        InferenceMode mode,
        InferenceRunState state,
        InferenceStage stage,
        long sequence,
        String profileId,
        String profileSnapshotJson,
        String replayFixtureId,
        String inputFingerprint,
        Optional<UUID> retryOfRunId,
        boolean cancellationRequested,
        Optional<InferenceLease> lease,
        Optional<String> failureCode,
        String checkpointJson,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> finishedAt,
        List<InferenceRunInput> inputs
) {
    public InferenceRunSnapshot {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(stage, "stage");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        profileId = requireText(profileId, "profileId");
        profileSnapshotJson = requireText(profileSnapshotJson, "profileSnapshotJson");
        replayFixtureId = requireText(replayFixtureId, "replayFixtureId");
        if (inputFingerprint == null || !inputFingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("inputFingerprint must be a SHA-256 hex digest");
        }
        retryOfRunId = Objects.requireNonNull(retryOfRunId, "retryOfRunId");
        lease = Objects.requireNonNull(lease, "lease");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        checkpointJson = requireText(checkpointJson, "checkpointJson");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        if (state == InferenceRunState.RUNNING && lease.isEmpty()) {
            throw new IllegalArgumentException("RUNNING run must carry a lease");
        }
        if (state != InferenceRunState.RUNNING && lease.isPresent()) {
            throw new IllegalArgumentException("only RUNNING run may carry a lease");
        }
        if (state.terminal() != finishedAt.isPresent()) {
            throw new IllegalArgumentException("terminal state and finishedAt must agree");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
