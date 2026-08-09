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
        if (results.isEmpty()) return new LiveEvaluationSlice(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                LiveEvaluationDiagnostics.empty()
        );
        var expectedEntities = sum(results, LiveEvaluationResult::expectedEntityCount);
        var actualEntities = sum(results, LiveEvaluationResult::actualEntityCount);
        var matchedEntities = sum(results, LiveEvaluationResult::matchedEntityCount);
        var expectedFields = sum(results, LiveEvaluationResult::expectedFieldCount);
        var actualFields = sum(results, LiveEvaluationResult::actualFieldCount);
        var matchedFields = sum(results, LiveEvaluationResult::matchedFieldCount);
        var expectedTypes = sum(results, LiveEvaluationResult::supportedTypeExpectedCount);
        var matchedTypes = sum(results, LiveEvaluationResult::supportedTypeMatchedCount);
        var expectedEdges = sum(results, LiveEvaluationResult::expectedEdgeCount);
        var actualEdges = sum(results, LiveEvaluationResult::actualEdgeCount);
        var matchedEdges = sum(results, LiveEvaluationResult::matchedEdgeCount);
        var expectedEvidence = sum(results, LiveEvaluationResult::evidenceExpectedCount);
        var presentEvidence = sum(results, LiveEvaluationResult::evidencePresentCount);
        return new LiveEvaluationSlice(
                results.size(), (int) results.stream().filter(LiveEvaluationResult::passed).count(),
                ratio(results.stream().filter(LiveEvaluationResult::passed).count(), results.size()),
                average(results, LiveEvaluationResult::bundleContractBps),
                LiveEvaluationResult.precision(matchedEntities, actualEntities, expectedEntities),
                LiveEvaluationResult.recall(matchedEntities, expectedEntities),
                LiveEvaluationResult.f1(matchedEntities, expectedEntities, actualEntities),
                LiveEvaluationResult.precision(matchedFields, actualFields, expectedFields),
                LiveEvaluationResult.recall(matchedFields, expectedFields),
                LiveEvaluationResult.f1(matchedFields, expectedFields, actualFields),
                LiveEvaluationResult.ratioOrPerfect(matchedTypes, expectedTypes),
                LiveEvaluationResult.precision(matchedEdges, actualEdges, expectedEdges),
                LiveEvaluationResult.recall(matchedEdges, expectedEdges),
                LiveEvaluationResult.f1(matchedEdges, expectedEdges, actualEdges),
                LiveEvaluationResult.ratioOrPerfect(presentEvidence, expectedEvidence),
                average(results, LiveEvaluationResult::dagValidityBps),
                results.stream().mapToInt(LiveEvaluationResult::criticalHallucinationCount).sum(),
                results.stream().mapToInt(LiveEvaluationResult::blockerCount).sum(),
                diagnostics(results)
        );
    }

    private static LiveEvaluationDiagnostics diagnostics(List<LiveEvaluationResult> results) {
        return new LiveEvaluationDiagnostics(
                (int) results.stream().filter(result -> !"EVALUATED".equals(result.outcomeCode())).count(),
                (int) results.stream().filter(result -> result.bundleContractBps() != 10_000).count(),
                (int) results.stream().filter(result -> result.dagValidityBps() != 10_000).count(),
                Math.toIntExact(sum(results, LiveEvaluationResult::missingEntityCount)),
                Math.toIntExact(sum(results, LiveEvaluationResult::unexpectedEntityCount)),
                Math.toIntExact(sum(results, LiveEvaluationResult::missingFieldCount)),
                Math.toIntExact(sum(results, LiveEvaluationResult::unexpectedFieldCount)),
                Math.toIntExact(sum(results, LiveEvaluationResult::supportedTypeMismatchCount)),
                Math.toIntExact(sum(results, LiveEvaluationResult::missingEdgeCount)),
                Math.toIntExact(sum(results, LiveEvaluationResult::unexpectedEdgeCount)),
                Math.toIntExact(sum(results, LiveEvaluationResult::unsupportedAssertionCount))
        );
    }

    private static long sum(
            List<LiveEvaluationResult> results,
            java.util.function.ToIntFunction<LiveEvaluationResult> metric
    ) {
        return results.stream().mapToLong(metric::applyAsInt).sum();
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
