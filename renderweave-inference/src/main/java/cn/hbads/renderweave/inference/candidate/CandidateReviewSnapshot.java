package cn.hbads.renderweave.inference.candidate;

import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CandidateReviewSnapshot(
        InferenceRunSnapshot run,
        long candidateRevision,
        CandidateBundle original,
        CandidateBundle current,
        List<CandidateProblem> problems,
        Optional<CandidateBundle> finalCandidate,
        Optional<Instant> appliedAt
) {
    public CandidateReviewSnapshot {
        Objects.requireNonNull(run, "run");
        if (candidateRevision < 0) throw new IllegalArgumentException("candidateRevision must not be negative");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(current, "current");
        problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        finalCandidate = finalCandidate == null ? Optional.empty() : finalCandidate;
        appliedAt = appliedAt == null ? Optional.empty() : appliedAt;
        if (finalCandidate.isPresent() != appliedAt.isPresent()) {
            throw new IllegalArgumentException("finalCandidate and appliedAt must be present together");
        }
    }
}
