package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.run.InferenceStage;

import java.util.List;
import java.util.Objects;

record LiveWorkflowCheckpoint(
        String checkpointVersion,
        InferenceStage completedStage,
        int providerCalls,
        int repairRounds,
        VisualElementInventory elementInventory,
        VisualHierarchyPlan hierarchyPlan,
        VisualElementBindingPlan bindingPlan,
        boolean outputValid,
        CandidateBundle candidate,
        List<CandidateProblem> validationProblems
) {
    static final String VERSION = "renderweave-live-checkpoint/2.0";

    LiveWorkflowCheckpoint {
        if (!VERSION.equals(checkpointVersion)) throw new IllegalArgumentException("Unsupported live checkpoint");
        Objects.requireNonNull(completedStage, "completedStage");
        if (providerCalls < 0 || providerCalls > 5 || repairRounds < 0 || repairRounds > 2) {
            throw new IllegalArgumentException("Live checkpoint budgets are invalid");
        }
        if (hierarchyPlan != null && elementInventory == null) {
            throw new IllegalArgumentException("Visual hierarchy requires an element inventory");
        }
        if (bindingPlan != null && hierarchyPlan == null) {
            throw new IllegalArgumentException("Visual bindings require a hierarchy");
        }
        validationProblems = List.copyOf(Objects.requireNonNull(validationProblems, "validationProblems"));
        if (outputValid != (candidate != null)) {
            throw new IllegalArgumentException("Valid live output and candidate must agree");
        }
    }

    static LiveWorkflowCheckpoint started() {
        return new LiveWorkflowCheckpoint(
                VERSION, InferenceStage.NORMALIZE, 0, 0,
                null, null, null, false, null, List.of()
        );
    }

    static LiveWorkflowCheckpoint observed() {
        return started().withCompletedStage(InferenceStage.OBSERVE);
    }

    LiveWorkflowCheckpoint elementsObserved(VisualElementInventory inventory, int calls) {
        return new LiveWorkflowCheckpoint(
                VERSION, InferenceStage.OBSERVE, calls, repairRounds,
                Objects.requireNonNull(inventory, "inventory"), null, null,
                false, null, List.of()
        );
    }

    LiveWorkflowCheckpoint hierarchyAnalyzed(VisualHierarchyPlan hierarchy, int calls) {
        return new LiveWorkflowCheckpoint(
                VERSION, InferenceStage.HIERARCHY, calls, repairRounds,
                Objects.requireNonNull(elementInventory, "elementInventory"),
                Objects.requireNonNull(hierarchy, "hierarchy"), null,
                false, null, List.of()
        );
    }

    LiveWorkflowCheckpoint elementsBound(VisualElementBindingPlan bindings, int calls) {
        return new LiveWorkflowCheckpoint(
                VERSION, InferenceStage.ELEMENT_BINDING, calls, repairRounds,
                Objects.requireNonNull(elementInventory, "elementInventory"),
                Objects.requireNonNull(hierarchyPlan, "hierarchyPlan"),
                Objects.requireNonNull(bindings, "bindings"),
                false, null, List.of()
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
                VERSION, completed, calls, repairs,
                elementInventory, hierarchyPlan, bindingPlan, producedCandidate != null,
                producedCandidate, prevalidationProblems
        );
    }

    LiveWorkflowCheckpoint validated(List<CandidateProblem> problems) {
        return new LiveWorkflowCheckpoint(
                VERSION, InferenceStage.DETERMINISTIC_VALIDATE, providerCalls, repairRounds,
                elementInventory, hierarchyPlan, bindingPlan,
                outputValid, candidate, problems
        );
    }

    LiveWorkflowCheckpoint critiqued() {
        return new LiveWorkflowCheckpoint(
                VERSION, InferenceStage.CRITIQUE, providerCalls, repairRounds,
                elementInventory, hierarchyPlan, bindingPlan,
                outputValid, candidate, validationProblems
        );
    }

    private LiveWorkflowCheckpoint withCompletedStage(InferenceStage stage) {
        return new LiveWorkflowCheckpoint(
                VERSION, stage, providerCalls, repairRounds,
                elementInventory, hierarchyPlan, bindingPlan,
                outputValid, candidate, validationProblems
        );
    }
}
