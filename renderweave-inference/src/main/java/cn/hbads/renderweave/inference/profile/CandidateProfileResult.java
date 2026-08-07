package cn.hbads.renderweave.inference.profile;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;

import java.util.List;
import java.util.Objects;

public record CandidateProfileResult(
        CandidateBundle candidate,
        List<CandidateProblem> semanticProblems
) {
    public CandidateProfileResult {
        Objects.requireNonNull(candidate, "candidate");
        semanticProblems = List.copyOf(Objects.requireNonNull(semanticProblems, "semanticProblems"));
    }
}
