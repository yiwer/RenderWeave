package cn.hbads.renderweave.inference.eval;

import java.util.List;
import java.util.Map;

public record LiveEvaluationReport(
        String corpusVersion,
        String profileId,
        int evaluatedCaseCount,
        int corpusCaseCount,
        boolean complete,
        LiveEvaluationSlice global,
        Map<String, LiveEvaluationSlice> byMode,
        Map<String, LiveEvaluationSlice> byPartition,
        List<String> missingCaseIds
) {
    public LiveEvaluationReport {
        byMode = Map.copyOf(byMode);
        byPartition = Map.copyOf(byPartition);
        missingCaseIds = List.copyOf(missingCaseIds);
    }
}
