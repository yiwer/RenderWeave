package cn.hbads.renderweave.inference.replay;

import cn.hbads.renderweave.inference.candidate.InferenceCandidateSnapshot;
import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;
import cn.hbads.renderweave.inference.run.InferenceStage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transactional persistence boundary for replay attempts and the first review snapshot. */
public interface InferenceReplayStore {
    /** Persists a safe failed-call record while retaining the current durable stage. */
    default InferenceRunSnapshot recordAttempt(
            UUID runId,
            UUID leaseToken,
            InferenceAttempt attempt,
            Instant now
    ) {
        throw new UnsupportedOperationException("Attempt-only checkpoints are not supported");
    }

    InferenceRunSnapshot checkpointAttempt(
            UUID runId,
            UUID leaseToken,
            InferenceStage expectedStage,
            InferenceStage nextStage,
            String checkpointJson,
            InferenceAttempt attempt,
            Instant now
    );

    InferenceRunSnapshot completeForReview(
            UUID runId,
            UUID leaseToken,
            String candidateJson,
            String validationProblemsJson,
            Instant now
    );

    Optional<InferenceCandidateSnapshot> findCandidate(UUID runId);

    InferenceCandidateSnapshot saveCandidate(
            UUID runId,
            long expectedRevision,
            String candidateJson,
            String validationProblemsJson,
            Instant now
    );

    List<InferenceAttempt> attempts(UUID runId);
}
