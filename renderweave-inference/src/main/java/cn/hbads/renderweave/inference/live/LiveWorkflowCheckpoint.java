package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.run.InferenceStage;

import java.util.List;
import java.util.Objects;

record LiveWorkflowCheckpoint(
        String checkpointVersion,
        InferenceStage completedStage,
        int structureCalls,
        int repairRounds,
        boolean outputValid,
        CandidateBundle candidate,
        List<CandidateProblem> validationProblems
) {
    static final String VERSION = "renderweave-live-checkpoint/1.0";

    LiveWorkflowCheckpoint {
        if (!VERSION.equals(checkpointVersion)) throw new IllegalArgumentException("Unsupported live checkpoint");
        Objects.requireNonNull(completedStage, "completedStage");
        if (structureCalls < 0 || structureCalls > 3 || repairRounds < 0 || repairRounds > 2) {
            throw new IllegalArgumentException("Live checkpoint budgets are invalid");
        }
        validationProblems = List.copyOf(Objects.requireNonNull(validationProblems, "validationProblems"));
        if (outputValid != (candidate != null)) {
            throw new IllegalArgumentException("Valid live output and candidate must agree");
        }
    }

    static LiveWorkflowCheckpoint observed() {
        return new LiveWorkflowCheckpoint(
                VERSION, InferenceStage.OBSERVE, 0, 0, false, null, List.of()
        );
    }

    LiveWorkflowCheckpoint callResult(
            InferenceStage completed,
            int calls,
            int repairs,
            CandidateBundle producedCandidate
    ) {
        return callResult(completed, calls, repairs, producedCandidate, List.of());
    }

    LiveWorkflowCheckpoint callResult(
            InferenceStage completed,
            int calls,
            int repairs,
            CandidateBundle producedCandidate,
            List<CandidateProblem> prevalidationProblems
    ) {
        return new LiveWorkflowCheckpoint(
                VERSION, completed, calls, repairs, producedCandidate != null,
                producedCandidate, prevalidationProblems
        );
    }

    LiveWorkflowCheckpoint validated(List<CandidateProblem> problems) {
        return new LiveWorkflowCheckpoint(
                VERSION, InferenceStage.DETERMINISTIC_VALIDATE, structureCalls, repairRounds,
                outputValid, candidate, problems
        );
    }

    LiveWorkflowCheckpoint critiqued() {
        return new LiveWorkflowCheckpoint(
                VERSION, InferenceStage.CRITIQUE, structureCalls, repairRounds,
                outputValid, candidate, validationProblems
        );
    }
}
