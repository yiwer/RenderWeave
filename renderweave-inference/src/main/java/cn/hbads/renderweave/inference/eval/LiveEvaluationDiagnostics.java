package cn.hbads.renderweave.inference.eval;

/** Payload-free failure taxonomy derived from the evaluator's sufficient statistics. */
public record LiveEvaluationDiagnostics(
        int providerFailureCaseCount,
        int contractInvalidCaseCount,
        int dagInvalidCaseCount,
        int missingEntityCount,
        int unexpectedEntityCount,
        int missingFieldCount,
        int unexpectedFieldCount,
        int supportedTypeMismatchCount,
        int missingEdgeCount,
        int unexpectedEdgeCount,
        int unsupportedAssertionCount
) {
    public LiveEvaluationDiagnostics {
        if (providerFailureCaseCount < 0 || contractInvalidCaseCount < 0 || dagInvalidCaseCount < 0
                || missingEntityCount < 0 || unexpectedEntityCount < 0
                || missingFieldCount < 0 || unexpectedFieldCount < 0
                || supportedTypeMismatchCount < 0 || missingEdgeCount < 0
                || unexpectedEdgeCount < 0 || unsupportedAssertionCount < 0) {
            throw new IllegalArgumentException("Evaluation diagnostic counts must not be negative");
        }
    }

    static LiveEvaluationDiagnostics empty() {
        return new LiveEvaluationDiagnostics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
