package cn.hbads.renderweave.inference.eval;

import java.util.List;

public record LiveEvaluationResult(
        String caseId,
        boolean passed,
        int actualSchemaCount,
        int expectedSchemaCount,
        int rootFieldPrecisionBps,
        int rootFieldRecallBps,
        int rootShapeAccuracyBps,
        int evidenceCoverageBps,
        int optionalitySafetyBps,
        int blockerCount,
        List<String> missingRootFields,
        List<String> unexpectedRootFields,
        List<String> shapeMismatches
) {
    public LiveEvaluationResult {
        missingRootFields = List.copyOf(missingRootFields);
        unexpectedRootFields = List.copyOf(unexpectedRootFields);
        shapeMismatches = List.copyOf(shapeMismatches);
    }
}
