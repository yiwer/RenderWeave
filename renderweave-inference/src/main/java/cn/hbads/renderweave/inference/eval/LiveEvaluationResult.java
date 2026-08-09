package cn.hbads.renderweave.inference.eval;

import java.util.List;

/** Per-case sufficient statistics; reports aggregate them as micro metrics instead of averaging ratios. */
public record LiveEvaluationResult(
        String caseId,
        String outcomeCode,
        boolean passed,
        int bundleContractBps,
        int expectedEntityCount,
        int actualEntityCount,
        int matchedEntityCount,
        int expectedFieldCount,
        int actualFieldCount,
        int matchedFieldCount,
        int supportedTypeExpectedCount,
        int supportedTypeMatchedCount,
        int expectedEdgeCount,
        int actualEdgeCount,
        int matchedEdgeCount,
        int evidenceExpectedCount,
        int evidencePresentCount,
        int dagValidityBps,
        int criticalHallucinationCount,
        int blockerCount,
        List<String> missingEntities,
        List<String> unexpectedEntities,
        List<String> missingFields,
        List<String> unexpectedFields,
        List<String> typeMismatches,
        List<String> edgeMismatches
) {
    public LiveEvaluationResult {
        if (caseId == null || caseId.isBlank() || outcomeCode == null || outcomeCode.isBlank()) {
            throw new IllegalArgumentException("Evaluation identity is required");
        }
        validateBps(bundleContractBps, "bundleContractBps");
        validateBps(dagValidityBps, "dagValidityBps");
        validateCounts(expectedEntityCount, actualEntityCount, matchedEntityCount, "entity");
        validateCounts(expectedFieldCount, actualFieldCount, matchedFieldCount, "field");
        validateCounts(expectedEdgeCount, actualEdgeCount, matchedEdgeCount, "edge");
        if (supportedTypeExpectedCount < 0 || supportedTypeMatchedCount < 0
                || supportedTypeMatchedCount > supportedTypeExpectedCount
                || evidenceExpectedCount < 0 || evidencePresentCount < 0
                || evidencePresentCount > evidenceExpectedCount
                || criticalHallucinationCount < 0 || blockerCount < 0) {
            throw new IllegalArgumentException("Evaluation counts are invalid");
        }
        var structuralHallucinations = Math.addExact(
                Math.subtractExact(actualEntityCount, matchedEntityCount),
                Math.addExact(
                        Math.subtractExact(actualFieldCount, matchedFieldCount),
                        Math.subtractExact(actualEdgeCount, matchedEdgeCount)
                )
        );
        if (criticalHallucinationCount < structuralHallucinations) {
            throw new IllegalArgumentException("Critical hallucination decomposition is invalid");
        }
        missingEntities = List.copyOf(missingEntities);
        unexpectedEntities = List.copyOf(unexpectedEntities);
        missingFields = List.copyOf(missingFields);
        unexpectedFields = List.copyOf(unexpectedFields);
        typeMismatches = List.copyOf(typeMismatches);
        edgeMismatches = List.copyOf(edgeMismatches);
    }

    public int schemaEntityPrecisionBps() {
        return precision(matchedEntityCount, actualEntityCount, expectedEntityCount);
    }

    public int schemaEntityRecallBps() {
        return recall(matchedEntityCount, expectedEntityCount);
    }

    public int schemaEntityF1Bps() {
        return f1(matchedEntityCount, expectedEntityCount, actualEntityCount);
    }

    public int fieldPrecisionBps() {
        return precision(matchedFieldCount, actualFieldCount, expectedFieldCount);
    }

    public int fieldRecallBps() {
        return recall(matchedFieldCount, expectedFieldCount);
    }

    public int fieldF1Bps() {
        return f1(matchedFieldCount, expectedFieldCount, actualFieldCount);
    }

    public int supportedTypeAccuracyBps() {
        return ratioOrPerfect(supportedTypeMatchedCount, supportedTypeExpectedCount);
    }

    public int parentChildEdgePrecisionBps() {
        return precision(matchedEdgeCount, actualEdgeCount, expectedEdgeCount);
    }

    public int parentChildEdgeRecallBps() {
        return recall(matchedEdgeCount, expectedEdgeCount);
    }

    public int parentChildEdgeF1Bps() {
        return f1(matchedEdgeCount, expectedEdgeCount, actualEdgeCount);
    }

    public int evidenceCoverageBps() {
        return ratioOrPerfect(evidencePresentCount, evidenceExpectedCount);
    }

    public int missingEntityCount() {
        return expectedEntityCount - matchedEntityCount;
    }

    public int unexpectedEntityCount() {
        return actualEntityCount - matchedEntityCount;
    }

    public int missingFieldCount() {
        return expectedFieldCount - matchedFieldCount;
    }

    public int unexpectedFieldCount() {
        return actualFieldCount - matchedFieldCount;
    }

    public int supportedTypeMismatchCount() {
        return supportedTypeExpectedCount - supportedTypeMatchedCount;
    }

    public int missingEdgeCount() {
        return expectedEdgeCount - matchedEdgeCount;
    }

    public int unexpectedEdgeCount() {
        return actualEdgeCount - matchedEdgeCount;
    }

    /** Required/constraint/provenance violations and unsupported concretization of uncertain gold. */
    public int unsupportedAssertionCount() {
        return criticalHallucinationCount
                - unexpectedEntityCount() - unexpectedFieldCount() - unexpectedEdgeCount();
    }

    static int precision(long matched, long actual, long expected) {
        if (actual == 0) return expected == 0 ? 10_000 : 0;
        return ratio(matched, actual);
    }

    static int recall(long matched, long expected) {
        return expected == 0 ? 10_000 : ratio(matched, expected);
    }

    static int f1(long matched, long expected, long actual) {
        var denominator = Math.addExact(expected, actual);
        return denominator == 0 ? 10_000 : ratio(Math.multiplyExact(2L, matched), denominator);
    }

    static int ratioOrPerfect(long numerator, long denominator) {
        return denominator == 0 ? 10_000 : ratio(numerator, denominator);
    }

    private static int ratio(long numerator, long denominator) {
        return (int) Math.floorDiv(Math.multiplyExact(numerator, 10_000L), denominator);
    }

    private static void validateCounts(int expected, int actual, int matched, String name) {
        if (expected < 0 || actual < 0 || matched < 0 || matched > expected || matched > actual) {
            throw new IllegalArgumentException(name + " evaluation counts are invalid");
        }
    }

    private static void validateBps(int value, String name) {
        if (value < 0 || value > 10_000) throw new IllegalArgumentException(name + " is invalid");
    }
}
