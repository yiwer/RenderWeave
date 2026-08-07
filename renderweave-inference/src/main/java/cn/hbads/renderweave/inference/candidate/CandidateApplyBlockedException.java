package cn.hbads.renderweave.inference.candidate;

import java.util.List;

public final class CandidateApplyBlockedException extends RuntimeException {
    private final List<CandidateProblem> problems;

    public CandidateApplyBlockedException(List<CandidateProblem> problems) {
        super("Candidate still has " + problems.size() + " blocking problem(s)");
        this.problems = List.copyOf(problems);
        if (this.problems.isEmpty()) {
            throw new IllegalArgumentException("At least one blocking problem is required");
        }
    }

    public List<CandidateProblem> problems() {
        return problems;
    }
}
