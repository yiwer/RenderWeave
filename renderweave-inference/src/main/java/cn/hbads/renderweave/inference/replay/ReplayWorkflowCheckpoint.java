package cn.hbads.renderweave.inference.replay;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.run.InferenceStage;

import java.util.List;
import java.util.Objects;

record ReplayWorkflowCheckpoint(
        String checkpointVersion,
        InferenceStage completedStage,
        int structureCalls,
        int repairRounds,
        boolean outputValid,
        CandidateBundle candidate,
        List<CandidateProblem> semanticProblems,
        List<CandidateProblem> validationProblems
) {
    static final String VERSION = "renderweave-replay-checkpoint/1.0";

    ReplayWorkflowCheckpoint {
        if (!VERSION.equals(checkpointVersion)) {
            throw new IllegalArgumentException("Unsupported replay checkpoint version");
        }
        Objects.requireNonNull(completedStage, "completedStage");
        if (structureCalls < 0 || structureCalls > 3 || repairRounds < 0 || repairRounds > 2) {
            throw new IllegalArgumentException("Replay checkpoint budgets are invalid");
        }
        semanticProblems = List.copyOf(Objects.requireNonNull(semanticProblems, "semanticProblems"));
        validationProblems = List.copyOf(Objects.requireNonNull(validationProblems, "validationProblems"));
        if (outputValid != (candidate != null)) {
            throw new IllegalArgumentException("Valid replay output and candidate must agree");
        }
    }

    static ReplayWorkflowCheckpoint observed() {
        return new ReplayWorkflowCheckpoint(
                VERSION, InferenceStage.OBSERVE, 0, 0, false, null, List.of(), List.of()
        );
    }

    ReplayWorkflowCheckpoint structureResult(
            InferenceStage completed,
            int attempts,
            int repairs,
            CandidateBundle producedCandidate,
            List<CandidateProblem> producedProblems
    ) {
        return new ReplayWorkflowCheckpoint(
                VERSION, completed, attempts, repairs, producedCandidate != null,
                producedCandidate, producedProblems, List.of()
        );
    }

    ReplayWorkflowCheckpoint validated(List<CandidateProblem> problems) {
        return new ReplayWorkflowCheckpoint(
                VERSION, InferenceStage.DETERMINISTIC_VALIDATE, structureCalls, repairRounds,
                outputValid, candidate, semanticProblems, problems
        );
    }

    ReplayWorkflowCheckpoint critiqued() {
        return new ReplayWorkflowCheckpoint(
                VERSION, InferenceStage.CRITIQUE, structureCalls, repairRounds,
                outputValid, candidate, semanticProblems, validationProblems
        );
    }
}
