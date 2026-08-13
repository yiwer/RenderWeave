package cn.hbads.renderweave.inference.eval.visual;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Deterministic micro-aggregator for payload-safe RapidOCR sufficient statistics. */
public final class RapidOcrShadowReporter {
    public static final String VERSION = "renderweave-rapidocr-shadow-reporter/1.0";

    public RapidOcrShadowReport report(
            LayeredVisualCorpus corpus,
            RapidOcrShadowEvaluationIdentity identity,
            List<RapidOcrShadowCaseRecord> first,
            List<RapidOcrShadowCaseRecord> second,
            int observationEquivalentCases
    ) {
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(identity, "identity");
        var firstRun = run(corpus, 1, first);
        var secondRun = run(corpus, 2, second);
        var secondById = secondRun.records().stream().collect(java.util.stream.Collectors.toMap(
                RapidOcrShadowCaseRecord::caseId, item -> item));
        var metricsEquivalent = Math.toIntExact(firstRun.records().stream()
                .filter(item -> item.metricsEquivalent(secondById.get(item.caseId()))).count());
        var deterministic = metricsEquivalent == 60 && observationEquivalentCases == 60;
        var facts = facts(corpus, firstRun.records(), secondById);
        var evidenceMissing = new RapidOcrShadowReport.TriggerDecision(
                false, false, "NOT_TRIGGERED_EVIDENCE_ABSENT");
        return new RapidOcrShadowReport(
                RapidOcrShadowReport.VERSION,
                identity.identity(),
                identity.components(),
                corpus.corpusIdentity(),
                corpus.annotationSetIdentity(),
                true,
                false,
                corpus.cases().size(),
                List.of(firstRun, secondRun),
                new RapidOcrShadowReport.Determinism(
                        60, metricsEquivalent, observationEquivalentCases, deterministic,
                        deterministic ? "DETERMINISTIC_TWO_RUNS" : "SECOND_RUN_DRIFT"
                ),
                facts,
                Map.of("R2", evidenceMissing, "R3", evidenceMissing, "R4", evidenceMissing, "R5", evidenceMissing),
                new RapidOcrShadowReport.ExternalProviderUsage(0, 0, 0)
        );
    }

    private static RapidOcrShadowReport.RunReport run(
            LayeredVisualCorpus corpus,
            int ordinal,
            List<RapidOcrShadowCaseRecord> source
    ) {
        source = List.copyOf(Objects.requireNonNull(source, "source"));
        var byId = new LinkedHashMap<String, RapidOcrShadowCaseRecord>();
        for (var item : source) {
            var expected = corpus.require(item.caseId());
            if (!expected.caseIdentity().equals(item.caseIdentity()) || expected.partition() != item.partition()
                    || !expected.domain().equals(item.domain()) || expected.difficulty() != item.difficulty()
                    || !expected.failureSlices().equals(item.failureSlices())
                    || byId.putIfAbsent(item.caseId(), item) != null) {
                throw new IllegalArgumentException("RAPIDOCR_SHADOW_CASE_CLOSURE_INVALID");
            }
        }
        var ordered = corpus.cases().stream().map(item -> byId.get(item.caseId())).toList();
        if (ordered.stream().anyMatch(Objects::isNull) || ordered.size() != 60) {
            throw new IllegalArgumentException("RAPIDOCR_SHADOW_CASE_SET_INCOMPLETE");
        }
        var partitions = new LinkedHashMap<String, RapidOcrShadowReport.Aggregate>();
        for (var partition : LayeredEvaluationRecord.Partition.values()) {
            partitions.put(partition.name(), aggregate(ordered.stream()
                    .filter(item -> item.partition() == partition).toList()));
        }
        var domains = new LinkedHashMap<String, RapidOcrShadowReport.Aggregate>();
        for (var domain : new TreeSet<>(corpus.cases().stream().map(LayeredVisualCorpus.Case::domain).toList())) {
            domains.put(domain, aggregate(ordered.stream().filter(item -> item.domain().equals(domain)).toList()));
        }
        var difficulties = new LinkedHashMap<String, RapidOcrShadowReport.Aggregate>();
        for (var difficulty : LayeredEvaluationRecord.Difficulty.values()) {
            difficulties.put(difficulty.name(), aggregate(ordered.stream()
                    .filter(item -> item.difficulty() == difficulty).toList()));
        }
        var diagnostic = new LinkedHashMap<String, RapidOcrShadowReport.Aggregate>();
        for (var slice : RapidOcrShadowCaseRecord.DiagnosticSlice.values()) {
            diagnostic.put(slice.name(), aggregate(ordered.stream()
                    .filter(item -> item.diagnosticSlices().contains(slice)).toList()));
        }
        var failures = new LinkedHashMap<String, RapidOcrShadowReport.Aggregate>();
        for (var slice : LayeredEvaluationRecord.FailureSlice.values()) {
            failures.put(slice.name(), aggregate(ordered.stream()
                    .filter(item -> item.failureSlices().contains(slice)).toList()));
        }
        return new RapidOcrShadowReport.RunReport(
                ordinal, 60, ordered.size(), true, ordered, aggregate(ordered),
                partitions, domains, difficulties, diagnostic, failures);
    }

    static RapidOcrShadowReport.Aggregate aggregate(List<RapidOcrShadowCaseRecord> source) {
        source = List.copyOf(source);
        var ocr = ocr(source);
        var layout = new RapidOcrShadowCaseRecord.LineLayoutStats(
                binary(source.stream().map(item -> item.layout().lines()).toList()),
                sum(source, item -> item.layout().centerContainedMatches()),
                sum(source, item -> item.layout().predictedCoverageBpsSum()),
                sum(source, item -> item.layout().goldCoverageBpsSum()),
                sum(source, item -> item.layout().observedRegions()));
        var order = new RapidOcrShadowCaseRecord.ReadingOrderStats(
                sum(source, item -> item.order().expectedEdges()),
                sum(source, item -> item.order().comparableEdges()),
                sum(source, item -> item.order().correctEdges()),
                source.stream().allMatch(item -> item.order().allReferencedRegionsObserved()));
        var repeat = new RapidOcrShadowCaseRecord.RepeatObservabilityStats(
                sum(source, item -> item.repeat().expectedGroups()),
                sum(source, item -> item.repeat().completeGroups()),
                sum(source, item -> item.repeat().expectedItems()),
                sum(source, item -> item.repeat().completeItems()),
                sum(source, item -> item.repeat().expectedMemberships()),
                sum(source, item -> item.repeat().observableMemberships()));
        var confidence = new RapidOcrShadowCaseRecord.ConfidenceStats(
                sum(source, item -> item.confidence().observations()),
                sum(source, item -> item.confidence().nativeValueBpsSum()),
                sum(source, item -> item.confidence().lowCount()),
                sum(source, item -> item.confidence().mediumCount()),
                sum(source, item -> item.confidence().highCount()));
        var latency = source.stream().map(RapidOcrShadowCaseRecord::acquisitionMicros).sorted().toList();
        return new RapidOcrShadowReport.Aggregate(
                source.size(), ocr, layout, order, repeat, confidence,
                new RapidOcrShadowReport.LatencyPercentiles(
                        latency.size(), percentile(latency, 50), percentile(latency, 95)),
                metricSummary(ocr, layout, order, repeat, confidence));
    }

    static Map<String, Integer> metricSummary(
            LayeredEvaluationRecord.OcrStats ocr,
            RapidOcrShadowCaseRecord.LineLayoutStats layout,
            RapidOcrShadowCaseRecord.ReadingOrderStats order,
            RapidOcrShadowCaseRecord.RepeatObservabilityStats repeat,
            RapidOcrShadowCaseRecord.ConfidenceStats confidence
    ) {
        var result = new TreeMap<String, Integer>();
        result.put("ocr.cer", ocr.cerBps());
        result.put("ocr.wer", ocr.werBps());
        result.put("ocr.completeMissRate", errorRate(ocr.completeMissCases(), ocr.cases()));
        result.put("ocr.hallucinationRate", errorRate(ocr.hallucinationCases(), ocr.cases()));
        result.put("layout.linePrecision", layout.lines().precisionBps());
        result.put("layout.lineRecall", layout.lines().recallBps());
        result.put("layout.meanPredictedCoverage", layout.meanPredictedCoverageBps());
        result.put("layout.meanGoldCoverage", layout.meanGoldCoverageBps());
        result.put("order.comparableCoverage", successRate(order.comparableEdges(), order.expectedEdges()));
        result.put("order.accuracy", order.accuracyBps());
        result.put("repeat.groupRecall", successRate(repeat.completeGroups(), repeat.expectedGroups()));
        result.put("repeat.itemRecall", successRate(repeat.completeItems(), repeat.expectedItems()));
        result.put("repeat.membershipRecall", repeat.membershipRecallBps());
        result.put("confidence.meanNativeValue", confidence.meanNativeValueBps());
        return Map.copyOf(result);
    }

    private static LayeredEvaluationRecord.OcrStats ocr(List<RapidOcrShadowCaseRecord> source) {
        return new LayeredEvaluationRecord.OcrStats(
                sum(source, item -> item.ocr().cases()),
                sum(source, item -> item.ocr().referenceCharacters()),
                sum(source, item -> item.ocr().predictedCharacters()),
                sum(source, item -> item.ocr().characterSubstitutions()),
                sum(source, item -> item.ocr().characterInsertions()),
                sum(source, item -> item.ocr().characterDeletions()),
                sum(source, item -> item.ocr().referenceWords()),
                sum(source, item -> item.ocr().predictedWords()),
                sum(source, item -> item.ocr().wordSubstitutions()),
                sum(source, item -> item.ocr().wordInsertions()),
                sum(source, item -> item.ocr().wordDeletions()),
                sum(source, item -> item.ocr().emptyReferenceCases()),
                sum(source, item -> item.ocr().hallucinationCases()),
                sum(source, item -> item.ocr().completeMissCases()));
    }

    private static RapidOcrShadowReport.EvidenceFacts facts(
            LayeredVisualCorpus corpus,
            List<RapidOcrShadowCaseRecord> first,
            Map<String, RapidOcrShadowCaseRecord> second
    ) {
        long gap = 0;
        long gapDev = 0;
        long gapHoldout = 0;
        long orderDev = 0;
        long orderHoldout = 0;
        long denseMissDev = 0;
        long denseMissHoldout = 0;
        for (var item : first) {
            if (!item.metricsEquivalent(second.get(item.caseId()))) continue;
            var stableGap = item.ocr().cerBps() > 0
                    || item.layout().lines().matched() < item.layout().lines().expected();
            if (stableGap) {
                gap++;
                if (item.partition() == LayeredEvaluationRecord.Partition.DEV) gapDev++;
                else gapHoldout++;
            }
            var recalledOrderOrRepeatError = item.layout().lines().matched() > 0
                    && (item.order().errors() > 0
                    || item.repeat().observableMemberships() < item.repeat().expectedMemberships());
            if (recalledOrderOrRepeatError) {
                if (item.partition() == LayeredEvaluationRecord.Partition.DEV) orderDev++;
                else orderHoldout++;
            }
            var denseOrSmall = !item.diagnosticSlices().isEmpty();
            if (denseOrSmall && item.layout().lines().matched() < item.layout().lines().expected()) {
                if (item.partition() == LayeredEvaluationRecord.Partition.DEV) denseMissDev++;
                else denseMissHoldout++;
            }
        }
        return new RapidOcrShadowReport.EvidenceFacts(
                gap, gapDev, gapHoldout, orderDev, orderHoldout, denseMissDev, denseMissHoldout,
                0, 0, 0);
    }

    private static LayeredEvaluationRecord.BinaryCounts binary(
            List<LayeredEvaluationRecord.BinaryCounts> source
    ) {
        return new LayeredEvaluationRecord.BinaryCounts(
                source.stream().mapToLong(LayeredEvaluationRecord.BinaryCounts::expected).sum(),
                source.stream().mapToLong(LayeredEvaluationRecord.BinaryCounts::predicted).sum(),
                source.stream().mapToLong(LayeredEvaluationRecord.BinaryCounts::matched).sum());
    }

    private static long sum(
            List<RapidOcrShadowCaseRecord> source,
            java.util.function.ToLongFunction<RapidOcrShadowCaseRecord> getter
    ) {
        long result = 0;
        for (var item : source) result = Math.addExact(result, getter.applyAsLong(item));
        return result;
    }

    private static long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) return 0;
        var index = Math.max(0, Math.toIntExact(Math.floorDiv(
                Math.addExact(Math.multiplyExact((long) sorted.size(), percentile), 99), 100)) - 1);
        return sorted.get(index);
    }

    private static int successRate(long numerator, long denominator) {
        return denominator == 0 ? 10_000
                : Math.toIntExact(Math.floorDiv(Math.multiplyExact(numerator, 10_000L), denominator));
    }

    private static int errorRate(long numerator, long denominator) {
        return denominator == 0 ? 0
                : Math.toIntExact(Math.floorDiv(Math.multiplyExact(numerator, 10_000L), denominator));
    }
}
