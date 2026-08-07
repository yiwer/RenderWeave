package cn.hbads.renderweave.inference.candidate;

import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;

import java.util.List;
import java.util.Objects;

public record CandidateReviewSnapshot(
        InferenceRunSnapshot run,
        long candidateRevision,
        CandidateBundle original,
        CandidateBundle current,
        List<CandidateProblem> problems
) {
    public CandidateReviewSnapshot {
        Objects.requireNonNull(run, "run");
        if (candidateRevision < 0) throw new IllegalArgumentException("candidateRevision must not be negative");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(current, "current");
        problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
    }
}
