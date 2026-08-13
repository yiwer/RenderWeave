package cn.hbads.renderweave.inference.eval.visual;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonical payload-safe scorecard for two actual corpus-v2 RapidOCR runs. */
public record RapidOcrShadowReport(
        String reportVersion,
        String evaluationIdentity,
        Map<String, String> evaluationComponents,
        String corpusIdentity,
        String annotationSetIdentity,
        boolean shadowDiagnostic,
        boolean certificationEligible,
        int expectedCaseCount,
        List<RunReport> runs,
        Determinism determinism,
        EvidenceFacts evidenceFacts,
        Map<String, TriggerDecision> triggers,
        ExternalProviderUsage externalProvider
) {
    public static final String VERSION = "renderweave-rapidocr-shadow-report/1.0";

    public RapidOcrShadowReport {
        if (!VERSION.equals(reportVersion)) throw invalid("RAPIDOCR_SHADOW_REPORT_VERSION_INVALID");
        evaluationComponents = Map.copyOf(Objects.requireNonNull(evaluationComponents, "evaluationComponents"));
        RapidOcrShadowEvaluationIdentity.fromComponents(evaluationComponents, evaluationIdentity);
        corpusIdentity = LayeredVisualAnnotation.requireIdentity(
                corpusIdentity, "RAPIDOCR_SHADOW_CORPUS_IDENTITY_INVALID");
        annotationSetIdentity = LayeredVisualAnnotation.requireIdentity(
                annotationSetIdentity, "RAPIDOCR_SHADOW_ANNOTATION_IDENTITY_INVALID");
        if (!shadowDiagnostic || certificationEligible || expectedCaseCount != 60) {
            throw invalid("RAPIDOCR_SHADOW_AUTHORITY_INVALID");
        }
        runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
        if (runs.size() != 2 || runs.get(0).runOrdinal() != 1 || runs.get(1).runOrdinal() != 2
                || runs.stream().anyMatch(run -> run.expectedCaseCount() != expectedCaseCount
                || run.observedCaseCount() != expectedCaseCount || !run.complete())) {
            throw invalid("RAPIDOCR_SHADOW_RUN_SET_INVALID");
        }
        Objects.requireNonNull(determinism, "determinism");
        Objects.requireNonNull(evidenceFacts, "evidenceFacts");
        triggers = Map.copyOf(Objects.requireNonNull(triggers, "triggers"));
        if (!triggers.keySet().equals(Set.of("R2", "R3", "R4", "R5"))
                || triggers.values().stream().anyMatch(Objects::isNull)) {
            throw invalid("RAPIDOCR_SHADOW_TRIGGER_SET_INVALID");
        }
        Objects.requireNonNull(externalProvider, "externalProvider");
        if (externalProvider.attempts() != 0 || externalProvider.reservations() != 0
                || externalProvider.costMicrosCny() != 0) {
            throw invalid("RAPIDOCR_SHADOW_PROVIDER_USAGE_FORBIDDEN");
        }
    }

    public record RunReport(
            int runOrdinal,
            int expectedCaseCount,
            int observedCaseCount,
            boolean complete,
            List<RapidOcrShadowCaseRecord> records,
            Aggregate global,
            Map<String, Aggregate> partitions,
            Map<String, Aggregate> domains,
            Map<String, Aggregate> difficulties,
            Map<String, Aggregate> diagnosticSlices,
            Map<String, Aggregate> failureSlices
    ) {
        public RunReport {
            if (runOrdinal < 1 || runOrdinal > 2 || expectedCaseCount != 60
                    || observedCaseCount != 60 || !complete) {
                throw invalid("RAPIDOCR_SHADOW_RUN_INVALID");
            }
            records = List.copyOf(Objects.requireNonNull(records, "records"));
            var ids = new HashSet<String>();
            if (records.size() != observedCaseCount || records.stream().anyMatch(item ->
                    item == null || !ids.add(item.caseId()))) {
                throw invalid("RAPIDOCR_SHADOW_RECORD_SET_INVALID");
            }
            Objects.requireNonNull(global, "global");
            partitions = requireSlices(partitions, Set.of("DEV", "HOLDOUT"), "PARTITION");
            if (partitions.values().stream().mapToLong(Aggregate::caseCount).sum() != observedCaseCount) {
                throw invalid("RAPIDOCR_SHADOW_PARTITION_ACCOUNTING_INVALID");
            }
            domains = requireNonEmptySlices(domains, "DOMAIN");
            difficulties = requireSlices(difficulties,
                    java.util.Arrays.stream(LayeredEvaluationRecord.Difficulty.values())
                            .map(Enum::name).collect(java.util.stream.Collectors.toSet()), "DIFFICULTY");
            diagnosticSlices = requireSlices(diagnosticSlices, Set.of("DENSE_TEXT", "SMALL_TEXT"), "DIAGNOSTIC");
            failureSlices = requireSlices(failureSlices,
                    java.util.Arrays.stream(LayeredEvaluationRecord.FailureSlice.values())
                            .map(Enum::name).collect(java.util.stream.Collectors.toSet()), "FAILURE");
            if (global.caseCount() != observedCaseCount
                    || domains.values().stream().mapToLong(Aggregate::caseCount).sum() != observedCaseCount
                    || difficulties.values().stream().mapToLong(Aggregate::caseCount).sum() != observedCaseCount) {
                throw invalid("RAPIDOCR_SHADOW_CASE_ACCOUNTING_INVALID");
            }
        }
    }

    public record Aggregate(
            long caseCount,
            LayeredEvaluationRecord.OcrStats ocr,
            RapidOcrShadowCaseRecord.LineLayoutStats layout,
            RapidOcrShadowCaseRecord.ReadingOrderStats order,
            RapidOcrShadowCaseRecord.RepeatObservabilityStats repeat,
            RapidOcrShadowCaseRecord.ConfidenceStats confidence,
            LatencyPercentiles acquisitionLatency,
            Map<String, Integer> metricsBps
    ) {
        public Aggregate {
            if (caseCount < 0) throw invalid("RAPIDOCR_SHADOW_AGGREGATE_CASE_COUNT_INVALID");
            Objects.requireNonNull(ocr, "ocr");
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(order, "order");
            Objects.requireNonNull(repeat, "repeat");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(acquisitionLatency, "acquisitionLatency");
            metricsBps = Map.copyOf(Objects.requireNonNull(metricsBps, "metricsBps"));
            if (ocr.cases() != caseCount || acquisitionLatency.count() != caseCount
                    || !metricsBps.equals(RapidOcrShadowReporter.metricSummary(
                    ocr, layout, order, repeat, confidence))) {
                throw invalid("RAPIDOCR_SHADOW_AGGREGATE_DRIFT");
            }
        }
    }

    public record LatencyPercentiles(long count, long p50Micros, long p95Micros) {
        public LatencyPercentiles {
            if (count < 0 || p50Micros < 0 || p95Micros < p50Micros
                    || count == 0 && (p50Micros != 0 || p95Micros != 0)) {
                throw invalid("RAPIDOCR_SHADOW_LATENCY_INVALID");
            }
        }
    }

    public record Determinism(
            int comparedCases,
            int metricsEquivalentCases,
            int observationEquivalentCases,
            boolean deterministic,
            String verdictCode
    ) {
        public Determinism {
            if (comparedCases != 60 || metricsEquivalentCases < 0 || metricsEquivalentCases > comparedCases
                    || observationEquivalentCases < 0 || observationEquivalentCases > comparedCases
                    || deterministic != (metricsEquivalentCases == comparedCases
                    && observationEquivalentCases == comparedCases)
                    || verdictCode == null || !verdictCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw invalid("RAPIDOCR_SHADOW_DETERMINISM_INVALID");
            }
        }
    }

    public record EvidenceFacts(
            long stableOcrOrLayoutGapCases,
            long stableOcrOrLayoutGapDevCases,
            long stableOcrOrLayoutGapHoldoutCases,
            long recalledOrderOrRepeatErrorDevCases,
            long recalledOrderOrRepeatErrorHoldoutCases,
            long denseOrSmallTextMissDevCases,
            long denseOrSmallTextMissHoldoutCases,
            long challengerRiskReviews,
            long strictShapeProtocolEvidenceCases,
            long oracleCropImprovementCases
    ) {
        public EvidenceFacts {
            for (var value : new long[]{stableOcrOrLayoutGapCases, stableOcrOrLayoutGapDevCases,
                    stableOcrOrLayoutGapHoldoutCases, recalledOrderOrRepeatErrorDevCases,
                    recalledOrderOrRepeatErrorHoldoutCases, denseOrSmallTextMissDevCases,
                    denseOrSmallTextMissHoldoutCases, challengerRiskReviews,
                    strictShapeProtocolEvidenceCases, oracleCropImprovementCases}) {
                if (value < 0) throw invalid("RAPIDOCR_SHADOW_EVIDENCE_FACT_INVALID");
            }
        }
    }

    public record TriggerDecision(
            boolean requiredEvidencePresent,
            boolean triggered,
            String reasonCode
    ) {
        public TriggerDecision {
            if (triggered && !requiredEvidencePresent || reasonCode == null
                    || !reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw invalid("RAPIDOCR_SHADOW_TRIGGER_INVALID");
            }
        }
    }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) {
        public ExternalProviderUsage {
            if (attempts < 0 || reservations < 0 || costMicrosCny < 0) {
                throw invalid("RAPIDOCR_SHADOW_PROVIDER_USAGE_INVALID");
            }
        }
    }

    private static Map<String, Aggregate> requireSlices(
            Map<String, Aggregate> value,
            Set<String> keys,
            String name
    ) {
        var result = Map.copyOf(Objects.requireNonNull(value, name));
        if (!result.keySet().equals(keys) || result.values().stream().anyMatch(Objects::isNull)) {
            throw invalid("RAPIDOCR_SHADOW_" + name + "_SLICES_INVALID");
        }
        return result;
    }

    private static Map<String, Aggregate> requireNonEmptySlices(Map<String, Aggregate> value, String name) {
        var result = Map.copyOf(Objects.requireNonNull(value, name));
        if (result.isEmpty() || result.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || !entry.getKey().matches("[a-z][a-z0-9-]{0,63}") || entry.getValue() == null)) {
            throw invalid("RAPIDOCR_SHADOW_" + name + "_SLICES_INVALID");
        }
        return result;
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
