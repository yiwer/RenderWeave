package cn.hbads.renderweave.inference.eval.visual;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic micro-aggregation; never averages already-rounded per-case ratios. */
public final class VisualStageReporter {
    public VisualStageReport report(
            VisualStageCorpus corpus,
            List<VisualStageEvaluationResult> results
    ) {
        Objects.requireNonNull(corpus, "corpus");
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        var byCaseId = new LinkedHashMap<String, VisualStageEvaluationResult>();
        for (var result : results) {
            var gold = corpus.require(result.caseId());
            if (gold.partition() != result.partition() || gold.style() != result.style()
                    || gold.scene().domainPack() != result.domainPack()) {
                throw new IllegalArgumentException("Visual result slice identity does not match gold");
            }
            if (byCaseId.putIfAbsent(result.caseId(), result) != null) {
                throw new IllegalArgumentException("Duplicate visual result " + result.caseId());
            }
        }
        var missing = corpus.cases().stream().map(VisualStageCorpus.EvaluationCase::caseId)
                .filter(caseId -> !byCaseId.containsKey(caseId)).toList();
        return new VisualStageReport(
                VisualStageReport.VERSION, VisualStageCorpus.VERSION, corpus.sourceSha256(),
                corpus.cases().size(), results.size(), missing.isEmpty(), missing,
                aggregate(results),
                slices(VisualStageCorpus.Partition.values(), results,
                        item -> item.partition().name()),
                slices(VisualStageCorpus.Style.values(), results,
                        item -> item.style().name()),
                slices(VisualStageCorpus.DomainPack.values(), results,
                        item -> item.domainPack().name())
        );
    }

    private static <E extends Enum<E>> Map<String, VisualStageReport.Aggregate> slices(
            E[] values,
            List<VisualStageEvaluationResult> source,
            java.util.function.Function<VisualStageEvaluationResult, String> classifier
    ) {
        var result = new LinkedHashMap<String, VisualStageReport.Aggregate>();
        for (var value : values) {
            result.put(value.name(), aggregate(source.stream()
                    .filter(item -> classifier.apply(item).equals(value.name())).toList()));
        }
        return Map.copyOf(result);
    }

    private static VisualStageReport.Aggregate aggregate(List<VisualStageEvaluationResult> source) {
        var slots = combine(source.stream().map(VisualStageEvaluationResult::slots).toList());
        var groups = combine(source.stream().map(VisualStageEvaluationResult::groups).toList());
        var grounding = new VisualStageEvaluationResult.GroundingMetrics(
                source.stream().mapToInt(item -> item.grounding().expected()).sum(),
                source.stream().mapToInt(item -> item.grounding().semanticallyMatched()).sum(),
                source.stream().mapToInt(item -> item.grounding().matchedAtIou50()).sum(),
                source.stream().mapToLong(item -> item.grounding().matchedIouBpsSum()).sum()
        );
        var entities = combine(source.stream().map(VisualStageEvaluationResult::entities).toList());
        var relationships = combine(source.stream().map(VisualStageEvaluationResult::relationships).toList());
        var bindings = combine(source.stream().map(VisualStageEvaluationResult::bindings).toList());
        var survival = new VisualStageReport.SurvivalAggregate(
                source.stream().mapToInt(item -> item.survival().expectedSlots()).sum(),
                source.stream().mapToInt(item -> item.survival().observedSlots()).sum(),
                source.stream().mapToInt(item -> item.survival().correctlyBoundSlots()).sum(),
                source.stream().mapToInt(item -> item.survival().candidateSlots()).sum()
        );
        var calibration = new ArrayList<VisualStageEvaluationResult.CalibrationBin>();
        for (var binIndex = 0; binIndex < 10; binIndex++) {
            var index = binIndex;
            calibration.add(new VisualStageEvaluationResult.CalibrationBin(
                    index,
                    source.stream().mapToInt(item -> item.calibrationBins().get(index).count()).sum(),
                    source.stream().mapToInt(item -> item.calibrationBins().get(index).correct()).sum(),
                    source.stream().mapToLong(item -> item.calibrationBins().get(index).confidenceBpsSum()).sum(),
                    source.stream().mapToLong(item -> item.calibrationBins().get(index).squaredErrorBpsSum()).sum()
            ));
        }
        return new VisualStageReport.Aggregate(
                source.size(),
                Math.toIntExact(source.stream().filter(item -> item.finalCandidate().passed()).count()),
                source.stream().mapToInt(VisualStageEvaluationResult::providerCalls).sum(),
                Math.toIntExact(source.stream().filter(item -> item.repairRounds() > 0).count()),
                Math.toIntExact(source.stream().filter(item -> item.repairRounds() > 0
                        && item.finalCandidate().passed()).count()),
                slots, groups, grounding, entities, relationships, bindings, survival,
                source.stream().mapToLong(VisualStageEvaluationResult::treeEditDistance).sum(),
                source.stream().mapToLong(VisualStageEvaluationResult::treeEditDenominator).sum(),
                List.copyOf(calibration), finalAggregate(source)
        );
    }

    private static VisualStageReport.FinalCandidateAggregate finalAggregate(
            List<VisualStageEvaluationResult> source
    ) {
        return new VisualStageReport.FinalCandidateAggregate(
                source.size(),
                Math.toIntExact(source.stream().filter(item -> item.finalCandidate().passed()).count()),
                source.stream().mapToLong(item -> item.finalCandidate().bundleContractBps()).sum(),
                combine(source.stream().map(item -> item.finalCandidate().entities()).toList()),
                combine(source.stream().map(item -> item.finalCandidate().fields()).toList()),
                combine(source.stream().map(item -> item.finalCandidate().relationships()).toList()),
                source.stream().mapToInt(item -> item.finalCandidate().supportedTypeExpected()).sum(),
                source.stream().mapToInt(item -> item.finalCandidate().supportedTypeMatched()).sum(),
                source.stream().mapToInt(item -> item.finalCandidate().evidenceExpected()).sum(),
                source.stream().mapToInt(item -> item.finalCandidate().evidencePresent()).sum(),
                source.stream().mapToLong(item -> item.finalCandidate().dagValidityBps()).sum(),
                source.stream().mapToInt(item -> item.finalCandidate().criticalHallucinations()).sum(),
                source.stream().mapToInt(item -> item.finalCandidate().blockers()).sum()
        );
    }

    private static VisualStageEvaluationResult.StageCounts combine(
            List<VisualStageEvaluationResult.StageCounts> values
    ) {
        return new VisualStageEvaluationResult.StageCounts(
                values.stream().mapToInt(VisualStageEvaluationResult.StageCounts::expected).sum(),
                values.stream().mapToInt(VisualStageEvaluationResult.StageCounts::actual).sum(),
                values.stream().mapToInt(VisualStageEvaluationResult.StageCounts::matched).sum()
        );
    }
}
