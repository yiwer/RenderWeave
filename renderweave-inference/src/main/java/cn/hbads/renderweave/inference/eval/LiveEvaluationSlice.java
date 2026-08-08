package cn.hbads.renderweave.inference.eval;

public record LiveEvaluationSlice(
        int caseCount,
        int passedCount,
        int passRateBps,
        int rootFieldPrecisionBps,
        int rootFieldRecallBps,
        int rootShapeAccuracyBps,
        int evidenceCoverageBps,
        int optionalitySafetyBps,
        int blockerCount
) { }
