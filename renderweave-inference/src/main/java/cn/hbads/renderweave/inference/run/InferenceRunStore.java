package cn.hbads.renderweave.inference.run;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InferenceRunStore {
    CreationResult create(NewInferenceRun command);

    Optional<InferenceRunSnapshot> find(UUID runId);

    RunPage list(int page, int size);

    Optional<InferenceRunSnapshot> claimNext(String workerId, Instant now, Duration leaseDuration);

    /** Claims only a network-enabled run. Replay workers must continue to use {@link #claimNext}. */
    default Optional<InferenceRunSnapshot> claimNextLive(
            String workerId,
            Instant now,
            Duration leaseDuration
    ) {
        throw new UnsupportedOperationException("Live inference claiming is not supported");
    }

    Optional<InferenceRunSnapshot> claim(UUID runId, String workerId, Instant now, Duration leaseDuration);

    boolean renewLease(UUID runId, UUID leaseToken, Instant now, Duration leaseDuration);

    InferenceRunSnapshot checkpoint(
            UUID runId,
            UUID leaseToken,
            InferenceStage expectedStage,
            InferenceStage nextStage,
            String checkpointJson,
            Instant now
    );

    InferenceRunSnapshot requestCancellation(UUID runId, Instant now);

    InferenceRunSnapshot acknowledgeCancellation(UUID runId, UUID leaseToken, Instant now);

    InferenceRunSnapshot fail(UUID runId, UUID leaseToken, String failureCode, Instant now);

    CreationResult retry(UUID sourceRunId, UUID newRunId, String idempotencyKey, Instant now);

    List<InferenceArtifactDeletion> delete(UUID runId);

    List<InferenceArtifactDeletion> pendingArtifactDeletions(int limit);

    boolean confirmArtifactDeletion(String artifactId);

    List<InferenceRunEvent> eventsAfter(UUID runId, long sequenceExclusive, int limit);

    record CreationResult(InferenceRunSnapshot run, boolean created) { }

    record RunPage(int page, int size, long total, List<RunSummary> items) {
        public RunPage {
            if (page < 1 || size < 1 || size > 20 || total < 0) {
                throw new IllegalArgumentException("Invalid inference run page");
            }
            items = List.copyOf(items);
            if (items.size() > size) throw new IllegalArgumentException("Inference run page exceeds size");
        }
    }

    record RunSummary(
            UUID runId,
            String mode,
            String state,
            String stage,
            long sequence,
            String profileId,
            String sourceReference,
            boolean cancellationRequested,
            UUID retryOfRunId,
            String failureCode,
            Long candidateRevision,
            Instant createdAt,
            Instant updatedAt,
            Instant finishedAt
    ) { }
}
