package cn.hbads.renderweave.inference.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Aggregates only executed cases and makes incomplete certification evidence explicit. */
public final class LiveEvaluationReporter {
    public LiveEvaluationReport report(
            String profileId,
            LiveEvaluationCorpus corpus,
            List<LiveEvaluationResult> results
    ) {
        if (profileId == null || !profileId.matches("[a-z0-9][a-z0-9.-]{0,127}")) {
            throw new IllegalArgumentException("profileId is invalid");
        }
        Objects.requireNonNull(corpus, "corpus");
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        var byCase = new LinkedHashMap<String, LiveEvaluationResult>();
        for (var result : results) {
            corpus.require(result.caseId());
            if (byCase.putIfAbsent(result.caseId(), result) != null) {
                throw new IllegalArgumentException("Duplicate evaluation result " + result.caseId());
            }
        }
        var executed = corpus.cases().stream()
                .filter(item -> byCase.containsKey(item.caseId()))
                .toList();
        var missing = corpus.cases().stream()
                .map(LiveEvaluationCase::caseId)
                .filter(caseId -> !byCase.containsKey(caseId))
                .toList();
        return new LiveEvaluationReport(
                LiveEvaluationCorpus.VERSION, profileId, results.size(), corpus.cases().size(),
                missing.isEmpty(), slice(results),
                grouped(executed, byCase, item -> item.mode().name()),
                grouped(executed, byCase, item -> item.partition().name()),
                missing
        );
    }

    private static Map<String, LiveEvaluationSlice> grouped(
            List<LiveEvaluationCase> cases,
            Map<String, LiveEvaluationResult> results,
            Function<LiveEvaluationCase, String> classifier
    ) {
        var grouped = cases.stream().collect(Collectors.groupingBy(
                classifier, LinkedHashMap::new, Collectors.toList()
        ));
        var slices = new LinkedHashMap<String, LiveEvaluationSlice>();
        grouped.forEach((key, items) -> slices.put(key, slice(items.stream()
                .map(item -> results.get(item.caseId())).toList())));
        return slices;
    }

    private static LiveEvaluationSlice slice(List<LiveEvaluationResult> results) {
        if (results.isEmpty()) return new LiveEvaluationSlice(0, 0, 0, 0, 0, 0, 0, 0, 0);
        return new LiveEvaluationSlice(
                results.size(), (int) results.stream().filter(LiveEvaluationResult::passed).count(),
                ratio(results.stream().filter(LiveEvaluationResult::passed).count(), results.size()),
                average(results, LiveEvaluationResult::rootFieldPrecisionBps),
                average(results, LiveEvaluationResult::rootFieldRecallBps),
                average(results, LiveEvaluationResult::rootShapeAccuracyBps),
                average(results, LiveEvaluationResult::evidenceCoverageBps),
                average(results, LiveEvaluationResult::optionalitySafetyBps),
                results.stream().mapToInt(LiveEvaluationResult::blockerCount).sum()
        );
    }

    private static int average(
            List<LiveEvaluationResult> results,
            java.util.function.ToIntFunction<LiveEvaluationResult> metric
    ) {
        return (int) Math.floorDiv(results.stream().mapToLong(metric::applyAsInt).sum(), results.size());
    }

    private static int ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (int) Math.floorDiv(numerator * 10_000L, denominator);
    }
}
