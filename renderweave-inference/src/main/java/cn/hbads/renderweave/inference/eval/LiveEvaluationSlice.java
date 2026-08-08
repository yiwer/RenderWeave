package cn.hbads.renderweave.inference.eval;

public record LiveEvaluationSlice(
        int caseCount,
        int passedCount,
        int passRateBps,
        int bundleContractBps,
        int schemaEntityPrecisionBps,
        int schemaEntityRecallBps,
        int schemaEntityF1Bps,
        int fieldPrecisionBps,
        int fieldRecallBps,
        int fieldF1Bps,
        int supportedTypeAccuracyBps,
        int parentChildEdgePrecisionBps,
        int parentChildEdgeRecallBps,
        int parentChildEdgeF1Bps,
        int evidenceCoverageBps,
        int dagValidityBps,
        int criticalHallucinationCount,
        int blockerCount
) { }
