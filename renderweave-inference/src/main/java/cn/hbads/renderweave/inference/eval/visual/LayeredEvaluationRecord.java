package cn.hbads.renderweave.inference.eval.visual;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Payload-safe sufficient statistics for one immutable corpus case. */
public record LayeredEvaluationRecord(
        String recordVersion,
        String caseId,
        String caseIdentity,
        Partition partition,
        String domain,
        Difficulty difficulty,
        List<FailureSlice> failureSlices,
        String outcomeCode,
        OcrStats ocr,
        LayoutStats layout,
        OrderStats order,
        RepeatStats repeat,
        SemanticStats semantic,
        CandidateStats candidate,
        CalibrationStats calibration,
        RuntimeStats runtime
) {
    public static final String VERSION = "renderweave-layered-evaluation-record/1.0";

    public LayeredEvaluationRecord {
        if (!VERSION.equals(recordVersion)) throw invalid("RECORD_VERSION_INVALID");
        caseId = LayeredVisualAnnotation.requireId(caseId, "RECORD_CASE_ID_INVALID");
        caseIdentity = LayeredVisualAnnotation.requireIdentity(caseIdentity, "RECORD_CASE_IDENTITY_INVALID");
        Objects.requireNonNull(partition, "partition");
        if (domain == null || !domain.matches("[a-z][a-z0-9-]{0,63}")) throw invalid("RECORD_DOMAIN_INVALID");
        Objects.requireNonNull(difficulty, "difficulty");
        failureSlices = List.copyOf(Objects.requireNonNull(failureSlices, "failureSlices"));
        if (failureSlices.size() > FailureSlice.values().length
                || new HashSet<>(failureSlices).size() != failureSlices.size()
                || failureSlices.stream().anyMatch(Objects::isNull)) {
            throw invalid("RECORD_FAILURE_SLICES_INVALID");
        }
        if (outcomeCode == null || !outcomeCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw invalid("RECORD_OUTCOME_INVALID");
        }
        Objects.requireNonNull(ocr, "ocr");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(repeat, "repeat");
        Objects.requireNonNull(semantic, "semantic");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(calibration, "calibration");
        Objects.requireNonNull(runtime, "runtime");
    }

    public static LayeredEvaluationRecord empty(
            String caseId,
            String caseIdentity,
            Partition partition,
            String domain,
            Difficulty difficulty
    ) {
        var byKind = new EnumMap<LayeredVisualAnnotation.RegionKind, DetectionStats>(
                LayeredVisualAnnotation.RegionKind.class);
        for (var kind : LayeredVisualAnnotation.RegionKind.values()) byKind.put(kind, DetectionStats.empty());
        var bins = new ArrayList<CalibrationBin>();
        for (var index = 0; index < 10; index++) bins.add(new CalibrationBin(index, 0, 0, 0, 0));
        return new LayeredEvaluationRecord(
                VERSION, caseId, caseIdentity, partition, domain, difficulty, List.of(), "NOT_EVALUATED",
                OcrStats.empty(), new LayoutStats(byKind, BinaryCounts.empty(), 0),
                new OrderStats(BinaryCounts.empty(), 0, 0), RepeatStats.empty(), SemanticStats.empty(),
                CandidateStats.empty(), new CalibrationStats(bins, BinaryCounts.empty(), 0, 0, 0),
                RuntimeStats.empty());
    }

    @Override
    public String toString() {
        return "LayeredEvaluationRecord[recordVersion=" + recordVersion + ", caseId=" + caseId
                + ", caseIdentity=" + caseIdentity + ", partition=" + partition + ", domain=" + domain
                + ", difficulty=" + difficulty + ", outcomeCode=" + outcomeCode + "]";
    }

    public enum Partition { DEV, HOLDOUT }

    public enum Difficulty {
        BASELINE, DENSE_TEXT, MULTI_COLUMN, REPEATED_LIST, PROMPT_INJECTION, LOW_CONTRAST, NOISY
    }

    public enum FailureSlice {
        DENSE_TEXT, MULTI_COLUMN, REPEATED_LIST, PROMPT_INJECTION, OCR_MISS, LAYOUT_MISS,
        ORDER_ERROR, REPEAT_ERROR, SEMANTIC_ERROR, CANDIDATE_ERROR, RECOVERY
    }

    public record BinaryCounts(long expected, long predicted, long matched) {
        public BinaryCounts {
            nonNegative(expected, predicted, matched);
            if (matched > expected || matched > predicted) throw invalid("BINARY_COUNTS_INVALID");
        }

        public static BinaryCounts empty() { return new BinaryCounts(0, 0, 0); }

        public int precisionBps() {
            return predicted == 0 ? expected == 0 ? 10_000 : 0 : ratio(matched, predicted);
        }

        public int recallBps() { return expected == 0 ? 10_000 : ratio(matched, expected); }

        public int f1Bps() {
            var denominator = Math.addExact(expected, predicted);
            return denominator == 0 ? 10_000 : ratio(Math.multiplyExact(2, matched), denominator);
        }
    }

    public record OcrStats(
            long cases,
            long referenceCharacters,
            long predictedCharacters,
            long characterSubstitutions,
            long characterInsertions,
            long characterDeletions,
            long referenceWords,
            long predictedWords,
            long wordSubstitutions,
            long wordInsertions,
            long wordDeletions,
            long emptyReferenceCases,
            long hallucinationCases,
            long completeMissCases
    ) {
        public OcrStats {
            nonNegative(cases, referenceCharacters, predictedCharacters, characterSubstitutions,
                    characterInsertions, characterDeletions, referenceWords, predictedWords,
                    wordSubstitutions, wordInsertions, wordDeletions, emptyReferenceCases,
                    hallucinationCases, completeMissCases);
            if (emptyReferenceCases > cases || hallucinationCases > cases || completeMissCases > cases) {
                throw invalid("OCR_STATS_INVALID");
            }
        }

        public static OcrStats empty() { return new OcrStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }

        public int cerBps() {
            return errorRate(referenceCharacters,
                    characterSubstitutions + characterInsertions + characterDeletions);
        }

        public int werBps() {
            return errorRate(referenceWords, wordSubstitutions + wordInsertions + wordDeletions);
        }
    }

    /** Per-kind AP uses the frozen 10 thresholds .50 through .95 in five-point increments. */
    public record DetectionStats(
            long expected,
            long predicted,
            List<Long> matchedByIouThreshold,
            long semanticallyMatched,
            long matchedIouBpsSum,
            long ap5095BpsSum,
            long evaluatedCases
    ) {
        public DetectionStats {
            nonNegative(expected, predicted, semanticallyMatched, matchedIouBpsSum, ap5095BpsSum,
                    evaluatedCases);
            matchedByIouThreshold = List.copyOf(Objects.requireNonNull(matchedByIouThreshold,
                    "matchedByIouThreshold"));
            if (matchedByIouThreshold.size() != 10 || matchedByIouThreshold.stream().anyMatch(Objects::isNull)
                    || matchedByIouThreshold.stream().anyMatch(value -> value < 0 || value > expected
                    || value > predicted) || semanticallyMatched > expected || semanticallyMatched > predicted
                    || matchedIouBpsSum > Math.multiplyExact(10_000, semanticallyMatched)
                    || ap5095BpsSum > Math.multiplyExact(10_000, evaluatedCases)) {
                throw invalid("DETECTION_STATS_INVALID");
            }
        }

        public static DetectionStats empty() {
            return new DetectionStats(0, 0, java.util.Collections.nCopies(10, 0L), 0, 0, 0, 0);
        }

        public int meanAp5095Bps() {
            return evaluatedCases == 0 ? 10_000 : ratioRaw(ap5095BpsSum, evaluatedCases);
        }

        public int meanMatchedIouBps() {
            return semanticallyMatched == 0 ? 0 : ratioRaw(matchedIouBpsSum, semanticallyMatched);
        }
    }

    public record LayoutStats(
            Map<LayeredVisualAnnotation.RegionKind, DetectionStats> byKind,
            BinaryCounts evidence,
            long falseEvidence
    ) {
        public LayoutStats {
            byKind = Map.copyOf(Objects.requireNonNull(byKind, "byKind"));
            if (byKind.size() != LayeredVisualAnnotation.RegionKind.values().length) {
                throw invalid("LAYOUT_KIND_COVERAGE_INVALID");
            }
            for (var kind : LayeredVisualAnnotation.RegionKind.values()) {
                if (!byKind.containsKey(kind) || byKind.get(kind) == null) {
                    throw invalid("LAYOUT_KIND_COVERAGE_INVALID");
                }
            }
            Objects.requireNonNull(evidence, "evidence");
            nonNegative(falseEvidence);
            if (falseEvidence > evidence.predicted()) throw invalid("FALSE_EVIDENCE_INVALID");
        }
    }

    public record OrderStats(BinaryCounts precedenceEdges, long cycleCases, long evaluatedCases) {
        public OrderStats {
            Objects.requireNonNull(precedenceEdges, "precedenceEdges");
            nonNegative(cycleCases, evaluatedCases);
            if (cycleCases > evaluatedCases) throw invalid("ORDER_STATS_INVALID");
        }
    }

    public record RepeatStats(
            BinaryCounts groups,
            BinaryCounts items,
            long itemCountAbsoluteError,
            BinaryCounts memberships
    ) {
        public RepeatStats {
            Objects.requireNonNull(groups, "groups");
            Objects.requireNonNull(items, "items");
            Objects.requireNonNull(memberships, "memberships");
            nonNegative(itemCountAbsoluteError);
        }

        public static RepeatStats empty() {
            return new RepeatStats(BinaryCounts.empty(), BinaryCounts.empty(), 0, BinaryCounts.empty());
        }
    }

    public record SurvivalStats(long expectedSlots, long observedSlots, long boundSlots, long candidateSlots) {
        public SurvivalStats {
            nonNegative(expectedSlots, observedSlots, boundSlots, candidateSlots);
            if (observedSlots > expectedSlots || boundSlots > observedSlots || candidateSlots > boundSlots) {
                throw invalid("SURVIVAL_STATS_INVALID");
            }
        }

        public static SurvivalStats empty() { return new SurvivalStats(0, 0, 0, 0); }
    }

    public record SemanticStats(
            BinaryCounts slots,
            BinaryCounts groups,
            BinaryCounts entities,
            BinaryCounts relationships,
            BinaryCounts cardinalities,
            BinaryCounts bindings,
            BinaryCounts ownerContainment,
            SurvivalStats survival,
            long repairAttempts,
            long repairSuccesses
    ) {
        public SemanticStats {
            Objects.requireNonNull(slots, "slots");
            Objects.requireNonNull(groups, "groups");
            Objects.requireNonNull(entities, "entities");
            Objects.requireNonNull(relationships, "relationships");
            Objects.requireNonNull(cardinalities, "cardinalities");
            Objects.requireNonNull(bindings, "bindings");
            Objects.requireNonNull(ownerContainment, "ownerContainment");
            Objects.requireNonNull(survival, "survival");
            nonNegative(repairAttempts, repairSuccesses);
            if (repairSuccesses > repairAttempts) throw invalid("REPAIR_STATS_INVALID");
        }

        public static SemanticStats empty() {
            return new SemanticStats(BinaryCounts.empty(), BinaryCounts.empty(), BinaryCounts.empty(),
                    BinaryCounts.empty(), BinaryCounts.empty(), BinaryCounts.empty(), BinaryCounts.empty(),
                    SurvivalStats.empty(), 0, 0);
        }
    }

    public record CandidateStats(
            long evaluatedCases,
            long contractValidCases,
            BinaryCounts entities,
            BinaryCounts fields,
            BinaryCounts relationships,
            BinaryCounts supportedTypes,
            BinaryCounts evidence,
            long dagValidCases,
            long criticalHallucinations,
            long blockers,
            long topologyExpectedCases,
            long topologyPreservedCases
    ) {
        public CandidateStats {
            nonNegative(evaluatedCases, contractValidCases, dagValidCases, criticalHallucinations, blockers,
                    topologyExpectedCases, topologyPreservedCases);
            Objects.requireNonNull(entities, "entities");
            Objects.requireNonNull(fields, "fields");
            Objects.requireNonNull(relationships, "relationships");
            Objects.requireNonNull(supportedTypes, "supportedTypes");
            Objects.requireNonNull(evidence, "evidence");
            if (contractValidCases > evaluatedCases || dagValidCases > evaluatedCases
                    || topologyExpectedCases > evaluatedCases || topologyPreservedCases > topologyExpectedCases) {
                throw invalid("CANDIDATE_STATS_INVALID");
            }
        }

        public static CandidateStats empty() {
            return new CandidateStats(0, 0, BinaryCounts.empty(), BinaryCounts.empty(), BinaryCounts.empty(),
                    BinaryCounts.empty(), BinaryCounts.empty(), 0, 0, 0, 0, 0);
        }
    }

    public record CalibrationBin(
            int binIndex,
            long count,
            long correct,
            long confidenceBpsSum,
            long squaredErrorBpsSum
    ) {
        public CalibrationBin {
            if (binIndex < 0 || binIndex > 9) throw invalid("CALIBRATION_BIN_INDEX_INVALID");
            nonNegative(count, correct, confidenceBpsSum, squaredErrorBpsSum);
            if (correct > count || confidenceBpsSum > Math.multiplyExact(10_000, count)
                    || squaredErrorBpsSum > Math.multiplyExact(10_000, count)) {
                throw invalid("CALIBRATION_BIN_INVALID");
            }
        }
    }

    public record CalibrationStats(
            List<CalibrationBin> bins,
            BinaryCounts unresolved,
            long reviewRequiredReachedCases,
            long successfulCases,
            long evaluatedCases
    ) {
        public CalibrationStats {
            bins = List.copyOf(Objects.requireNonNull(bins, "bins"));
            if (bins.size() != 10) throw invalid("CALIBRATION_BIN_COUNT_INVALID");
            for (var index = 0; index < bins.size(); index++) {
                if (bins.get(index).binIndex() != index) throw invalid("CALIBRATION_BIN_ORDER_INVALID");
            }
            Objects.requireNonNull(unresolved, "unresolved");
            nonNegative(reviewRequiredReachedCases, successfulCases, evaluatedCases);
            if (reviewRequiredReachedCases > evaluatedCases || successfulCases > evaluatedCases) {
                throw invalid("CALIBRATION_CASE_COUNTS_INVALID");
            }
        }

        public int expectedCalibrationErrorBps() {
            var total = bins.stream().mapToLong(CalibrationBin::count).sum();
            if (total == 0) return 0;
            long weighted = 0;
            for (var bin : bins) {
                if (bin.count() == 0) continue;
                var confidence = ratioRaw(bin.confidenceBpsSum(), bin.count());
                var accuracy = ratio(bin.correct(), bin.count());
                weighted = Math.addExact(weighted,
                        Math.multiplyExact(bin.count(), Math.abs((long) confidence - accuracy)));
            }
            return ratioRaw(weighted, total);
        }

        public int brierScoreBps() {
            var total = bins.stream().mapToLong(CalibrationBin::count).sum();
            return total == 0 ? 0 : ratioRaw(bins.stream().mapToLong(CalibrationBin::squaredErrorBpsSum).sum(),
                    total);
        }
    }

    public enum Stage { ACQUISITION, HIERARCHY, ELEMENT_BINDING, CANDIDATE }

    public enum RecoveryCode { NONE, FIXED_RETRY, LEASE_RECOVERY }

    public record RuntimeStats(
            long scriptedCalls,
            long inputTokens,
            long outputTokens,
            long estimatedCostMicrosCny,
            long settledCostMicrosCny,
            Map<Stage, Long> latencyMicros,
            RecoveryCode recoveryCode,
            long recoveryCount,
            long acceptedStageReplayCount,
            long providerAttempts,
            long providerReservations,
            long externalProviderCostMicrosCny
    ) {
        public RuntimeStats {
            nonNegative(scriptedCalls, inputTokens, outputTokens, estimatedCostMicrosCny,
                    settledCostMicrosCny, recoveryCount, acceptedStageReplayCount, providerAttempts,
                    providerReservations, externalProviderCostMicrosCny);
            latencyMicros = Map.copyOf(Objects.requireNonNull(latencyMicros, "latencyMicros"));
            if (latencyMicros.size() > Stage.values().length
                    || latencyMicros.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                    || entry.getValue() == null || entry.getValue() < 0)) {
                throw invalid("RUNTIME_LATENCY_INVALID");
            }
            Objects.requireNonNull(recoveryCode, "recoveryCode");
        }

        public static RuntimeStats empty() {
            return new RuntimeStats(0, 0, 0, 0, 0, Map.of(), RecoveryCode.NONE, 0, 0, 0, 0, 0);
        }
    }

    static int ratio(long numerator, long denominator) {
        if (denominator == 0) return 10_000;
        return (int) Math.floorDiv(Math.multiplyExact(numerator, 10_000), denominator);
    }

    static int ratioRaw(long numerator, long denominator) {
        if (denominator == 0) return 0;
        return (int) Math.floorDiv(numerator, denominator);
    }

    private static int errorRate(long reference, long errors) {
        if (reference == 0) return errors == 0 ? 0 : 10_000;
        return ratio(errors, reference);
    }

    private static void nonNegative(long... values) {
        for (var value : values) if (value < 0) throw invalid("NEGATIVE_EVALUATION_STAT");
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
