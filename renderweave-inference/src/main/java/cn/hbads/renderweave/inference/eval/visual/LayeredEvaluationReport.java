package cn.hbads.renderweave.inference.eval.visual;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonical payload-safe R1 scorecard plus the sufficient records needed for independent replay. */
public record LayeredEvaluationReport(
        String reportVersion,
        String evaluationIdentity,
        Map<String, String> evaluationComponents,
        String corpusIdentity,
        String annotationSetIdentity,
        String recordSetIdentity,
        int expectedCaseCount,
        int observedCaseCount,
        boolean complete,
        List<String> missingCaseIds,
        List<RecordEntry> records,
        Aggregate global,
        Map<String, Aggregate> partitions,
        Map<String, Aggregate> domains,
        Map<String, Aggregate> difficulties,
        Map<String, Aggregate> failureSlices
) {
    public static final String VERSION = "renderweave-layered-evaluation-report/1.0";

    public LayeredEvaluationReport {
        if (!VERSION.equals(reportVersion)) throw invalid("LAYERED_REPORT_VERSION_INVALID");
        evaluationComponents = Map.copyOf(Objects.requireNonNull(evaluationComponents, "evaluationComponents"));
        LayeredEvaluationIdentity.fromComponents(evaluationComponents, evaluationIdentity);
        corpusIdentity = LayeredVisualAnnotation.requireIdentity(corpusIdentity,
                "LAYERED_REPORT_CORPUS_IDENTITY_INVALID");
        annotationSetIdentity = LayeredVisualAnnotation.requireIdentity(annotationSetIdentity,
                "LAYERED_REPORT_ANNOTATION_IDENTITY_INVALID");
        recordSetIdentity = LayeredVisualAnnotation.requireIdentity(recordSetIdentity,
                "LAYERED_REPORT_RECORD_SET_IDENTITY_INVALID");
        missingCaseIds = List.copyOf(Objects.requireNonNull(missingCaseIds, "missingCaseIds"));
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        if (expectedCaseCount != 60 || observedCaseCount != records.size() || observedCaseCount != 60
                || !complete || !missingCaseIds.isEmpty()) {
            throw invalid("LAYERED_REPORT_COMPLETENESS_INVALID");
        }
        var ids = new HashSet<String>();
        var recordIdentities = new HashSet<String>();
        var codec = new LayeredEvaluationJsonCodec();
        for (var entry : records) {
            if (!ids.add(entry.record().caseId()) || !recordIdentities.add(entry.recordIdentity())
                    || !codec.recordIdentity(entry.record()).equals(entry.recordIdentity())) {
                throw invalid("LAYERED_REPORT_RECORD_CLOSURE_INVALID");
            }
        }
        Objects.requireNonNull(global, "global");
        partitions = requireSlices(partitions, Set.of("DEV", "HOLDOUT"), "PARTITION");
        domains = requireDomainSlices(domains);
        difficulties = requireSlices(difficulties,
                java.util.Arrays.stream(LayeredEvaluationRecord.Difficulty.values()).map(Enum::name)
                        .collect(java.util.stream.Collectors.toSet()), "DIFFICULTY");
        failureSlices = requireSlices(failureSlices,
                java.util.Arrays.stream(LayeredEvaluationRecord.FailureSlice.values()).map(Enum::name)
                        .collect(java.util.stream.Collectors.toSet()), "FAILURE");
        if (global.caseCount() != observedCaseCount
                || partitions.values().stream().mapToLong(Aggregate::caseCount).sum() != observedCaseCount
                || domains.values().stream().mapToLong(Aggregate::caseCount).sum() != observedCaseCount
                || difficulties.values().stream().mapToLong(Aggregate::caseCount).sum() != observedCaseCount) {
            throw invalid("LAYERED_REPORT_CASE_ACCOUNTING_INVALID");
        }
    }

    public record RecordEntry(String recordIdentity, LayeredEvaluationRecord record) {
        public RecordEntry {
            recordIdentity = LayeredVisualAnnotation.requireIdentity(recordIdentity,
                    "LAYERED_REPORT_RECORD_IDENTITY_INVALID");
            Objects.requireNonNull(record, "record");
        }
    }

    public record Aggregate(
            long caseCount,
            LayeredEvaluationRecord.OcrStats ocr,
            LayeredEvaluationRecord.LayoutStats layout,
            LayeredEvaluationRecord.OrderStats order,
            LayeredEvaluationRecord.RepeatStats repeat,
            LayeredEvaluationRecord.SemanticStats semantic,
            LayeredEvaluationRecord.CandidateStats candidate,
            LayeredEvaluationRecord.CalibrationStats calibration,
            RuntimeAggregate runtime,
            Map<String, Integer> metricsBps
    ) {
        public Aggregate {
            if (caseCount < 0) throw invalid("LAYERED_AGGREGATE_CASE_COUNT_INVALID");
            Objects.requireNonNull(ocr, "ocr");
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(order, "order");
            Objects.requireNonNull(repeat, "repeat");
            Objects.requireNonNull(semantic, "semantic");
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(calibration, "calibration");
            Objects.requireNonNull(runtime, "runtime");
            metricsBps = Map.copyOf(Objects.requireNonNull(metricsBps, "metricsBps"));
            if (ocr.cases() != caseCount || order.evaluatedCases() != caseCount
                    || candidate.evaluatedCases() != caseCount || calibration.evaluatedCases() != caseCount) {
                throw invalid("LAYERED_AGGREGATE_ACCOUNTING_INVALID");
            }
            if (!metricsBps.equals(metricSummary(
                    ocr, layout, order, repeat, semantic, candidate, calibration))) {
                throw invalid("LAYERED_AGGREGATE_METRICS_DRIFT");
            }
        }
    }

    public record LatencyPercentiles(long count, long p50Micros, long p95Micros) {
        public LatencyPercentiles {
            if (count < 0 || p50Micros < 0 || p95Micros < 0 || p50Micros > p95Micros
                    || count == 0 && (p50Micros != 0 || p95Micros != 0)) {
                throw invalid("LAYERED_LATENCY_PERCENTILES_INVALID");
            }
        }
    }

    public record RuntimeAggregate(
            long scriptedCalls,
            long inputTokens,
            long outputTokens,
            long estimatedCostMicrosCny,
            long settledCostMicrosCny,
            Map<String, LatencyPercentiles> latency,
            Map<String, Long> recoveryCodes,
            long recoveryCount,
            long acceptedStageReplayCount,
            long providerAttempts,
            long providerReservations,
            long externalProviderCostMicrosCny
    ) {
        public RuntimeAggregate {
            if (scriptedCalls < 0 || inputTokens < 0 || outputTokens < 0 || estimatedCostMicrosCny < 0
                    || settledCostMicrosCny < 0 || recoveryCount < 0 || acceptedStageReplayCount < 0
                    || providerAttempts < 0 || providerReservations < 0 || externalProviderCostMicrosCny < 0) {
                throw invalid("LAYERED_RUNTIME_AGGREGATE_INVALID");
            }
            latency = requireMap(latency,
                    java.util.Arrays.stream(LayeredEvaluationRecord.Stage.values()).map(Enum::name)
                            .collect(java.util.stream.Collectors.toSet()), "LATENCY");
            recoveryCodes = requireLongMap(recoveryCodes,
                    java.util.Arrays.stream(LayeredEvaluationRecord.RecoveryCode.values()).map(Enum::name)
                            .collect(java.util.stream.Collectors.toSet()), "RECOVERY");
        }
    }

    private static Map<String, Aggregate> requireSlices(
            Map<String, Aggregate> value,
            Set<String> expectedKeys,
            String name
    ) {
        var result = Map.copyOf(Objects.requireNonNull(value, name));
        if (!result.keySet().equals(expectedKeys) || result.values().stream().anyMatch(Objects::isNull)) {
            throw invalid("LAYERED_REPORT_" + name + "_SLICES_INVALID");
        }
        return result;
    }

    private static Map<String, Aggregate> requireDomainSlices(Map<String, Aggregate> value) {
        var result = Map.copyOf(Objects.requireNonNull(value, "domains"));
        if (result.isEmpty() || result.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || !entry.getKey().matches("[a-z][a-z0-9-]{0,63}") || entry.getValue() == null)) {
            throw invalid("LAYERED_REPORT_DOMAIN_SLICES_INVALID");
        }
        return result;
    }

    private static <T> Map<String, T> requireMap(
            Map<String, T> value,
            Set<String> expectedKeys,
            String name
    ) {
        var result = Map.copyOf(Objects.requireNonNull(value, name));
        if (!result.keySet().equals(expectedKeys) || result.values().stream().anyMatch(Objects::isNull)) {
            throw invalid("LAYERED_RUNTIME_" + name + "_INVALID");
        }
        return result;
    }

    private static Map<String, Long> requireLongMap(
            Map<String, Long> value,
            Set<String> expectedKeys,
            String name
    ) {
        var result = requireMap(value, expectedKeys, name);
        if (result.values().stream().anyMatch(item -> item < 0)) {
            throw invalid("LAYERED_RUNTIME_" + name + "_INVALID");
        }
        return result;
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    static Map<String, Integer> metricSummary(
            LayeredEvaluationRecord.OcrStats ocr,
            LayeredEvaluationRecord.LayoutStats layout,
            LayeredEvaluationRecord.OrderStats order,
            LayeredEvaluationRecord.RepeatStats repeat,
            LayeredEvaluationRecord.SemanticStats semantic,
            LayeredEvaluationRecord.CandidateStats candidate,
            LayeredEvaluationRecord.CalibrationStats calibration
    ) {
        var result = new java.util.TreeMap<String, Integer>();
        result.put("ocr.cer", ocr.cerBps());
        result.put("ocr.wer", ocr.werBps());
        result.put("ocr.emptyReferenceInsertionRate", errorRate(ocr.emptyReferenceCases(), ocr.cases()));
        result.put("ocr.hallucinationRate", errorRate(ocr.hallucinationCases(), ocr.cases()));
        result.put("ocr.completeMissRate", errorRate(ocr.completeMissCases(), ocr.cases()));
        for (var kind : LayeredVisualAnnotation.RegionKind.values()) {
            var detection = layout.byKind().get(kind);
            var counts = new LayeredEvaluationRecord.BinaryCounts(
                    detection.expected(), detection.predicted(), detection.semanticallyMatched());
            var prefix = "layout." + kind.name() + ".";
            result.put(prefix + "precision", counts.precisionBps());
            result.put(prefix + "recall", counts.recallBps());
            result.put(prefix + "ap5095", detection.meanAp5095Bps());
            result.put(prefix + "meanMatchedIou", detection.meanMatchedIouBps());
        }
        result.put("layout.evidenceRecall", layout.evidence().recallBps());
        result.put("layout.falseEvidenceRate", errorRate(layout.falseEvidence(), layout.evidence().predicted()));
        result.put("order.precision", order.precedenceEdges().precisionBps());
        result.put("order.recall", order.precedenceEdges().recallBps());
        result.put("order.f1", order.precedenceEdges().f1Bps());
        result.put("order.cycleRate", errorRate(order.cycleCases(), order.evaluatedCases()));
        result.put("repeat.groupRecall", repeat.groups().recallBps());
        result.put("repeat.itemRecall", repeat.items().recallBps());
        result.put("repeat.membershipAccuracy", repeat.memberships().f1Bps());
        result.put("semantic.slotRecall", semantic.slots().recallBps());
        result.put("semantic.groupRecall", semantic.groups().recallBps());
        result.put("semantic.entityF1", semantic.entities().f1Bps());
        result.put("semantic.relationshipF1", semantic.relationships().f1Bps());
        result.put("semantic.cardinalityAccuracy", semantic.cardinalities().recallBps());
        result.put("semantic.bindingAccuracy", semantic.bindings().recallBps());
        result.put("semantic.ownerContainment", semantic.ownerContainment().recallBps());
        result.put("semantic.observationSurvival", successRate(
                semantic.survival().observedSlots(), semantic.survival().expectedSlots()));
        result.put("semantic.bindingSurvival", successRate(
                semantic.survival().boundSlots(), semantic.survival().expectedSlots()));
        result.put("semantic.candidateSurvival", successRate(
                semantic.survival().candidateSlots(), semantic.survival().expectedSlots()));
        result.put("semantic.repairYield", successRate(
                semantic.repairSuccesses(), semantic.repairAttempts()));
        result.put("candidate.contractValidity", successRate(
                candidate.contractValidCases(), candidate.evaluatedCases()));
        result.put("candidate.entityF1", candidate.entities().f1Bps());
        result.put("candidate.fieldF1", candidate.fields().f1Bps());
        result.put("candidate.relationshipF1", candidate.relationships().f1Bps());
        result.put("candidate.supportedTypeAccuracy", candidate.supportedTypes().recallBps());
        result.put("candidate.evidenceCoverage", candidate.evidence().recallBps());
        result.put("candidate.dagValidity", successRate(candidate.dagValidCases(), candidate.evaluatedCases()));
        result.put("candidate.topologyPreservation", successRate(
                candidate.topologyPreservedCases(), candidate.topologyExpectedCases()));
        result.put("calibration.ece", calibration.expectedCalibrationErrorBps());
        result.put("calibration.brier", calibration.brierScoreBps());
        result.put("calibration.unresolvedPrecision", calibration.unresolved().precisionBps());
        result.put("calibration.reviewRequiredReachability", successRate(
                calibration.reviewRequiredReachedCases(), calibration.evaluatedCases()));
        result.put("calibration.success", successRate(
                calibration.successfulCases(), calibration.evaluatedCases()));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static int successRate(long numerator, long denominator) {
        return denominator == 0 ? 10_000 : (int) Math.floorDiv(Math.multiplyExact(numerator, 10_000), denominator);
    }

    private static int errorRate(long numerator, long denominator) {
        return denominator == 0 ? 0 : (int) Math.floorDiv(Math.multiplyExact(numerator, 10_000), denominator);
    }
}
