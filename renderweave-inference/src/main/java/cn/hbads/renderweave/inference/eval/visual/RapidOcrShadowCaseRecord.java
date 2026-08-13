package cn.hbads.renderweave.inference.eval.visual;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Payload-safe sufficient statistics for one actual RapidOCR shadow acquisition. */
public record RapidOcrShadowCaseRecord(
        String recordVersion,
        String caseId,
        String caseIdentity,
        LayeredEvaluationRecord.Partition partition,
        String domain,
        LayeredEvaluationRecord.Difficulty difficulty,
        List<LayeredEvaluationRecord.FailureSlice> failureSlices,
        List<DiagnosticSlice> diagnosticSlices,
        LayeredEvaluationRecord.OcrStats ocr,
        LineLayoutStats layout,
        ReadingOrderStats order,
        RepeatObservabilityStats repeat,
        ConfidenceStats confidence,
        int observationCount,
        long acquisitionMicros
) {
    public static final String VERSION = "renderweave-rapidocr-shadow-case-record/1.0";

    public RapidOcrShadowCaseRecord {
        if (!VERSION.equals(recordVersion)) throw invalid("RAPIDOCR_SHADOW_RECORD_VERSION_INVALID");
        caseId = LayeredVisualAnnotation.requireId(caseId, "RAPIDOCR_SHADOW_CASE_ID_INVALID");
        caseIdentity = LayeredVisualAnnotation.requireIdentity(
                caseIdentity, "RAPIDOCR_SHADOW_CASE_IDENTITY_INVALID");
        Objects.requireNonNull(partition, "partition");
        if (domain == null || !domain.matches("[a-z][a-z0-9-]{0,63}")) {
            throw invalid("RAPIDOCR_SHADOW_DOMAIN_INVALID");
        }
        Objects.requireNonNull(difficulty, "difficulty");
        failureSlices = List.copyOf(Objects.requireNonNull(failureSlices, "failureSlices"));
        if (new HashSet<>(failureSlices).size() != failureSlices.size()
                || failureSlices.stream().anyMatch(Objects::isNull)) {
            throw invalid("RAPIDOCR_SHADOW_SLICES_INVALID");
        }
        diagnosticSlices = List.copyOf(Objects.requireNonNull(diagnosticSlices, "diagnosticSlices"));
        if (new HashSet<>(diagnosticSlices).size() != diagnosticSlices.size()
                || diagnosticSlices.stream().anyMatch(Objects::isNull)) {
            throw invalid("RAPIDOCR_SHADOW_DIAGNOSTIC_SLICES_INVALID");
        }
        Objects.requireNonNull(ocr, "ocr");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(repeat, "repeat");
        Objects.requireNonNull(confidence, "confidence");
        if (observationCount < 0 || acquisitionMicros < 0
                || observationCount != layout.lines().predicted()
                || observationCount != confidence.observations()) {
            throw invalid("RAPIDOCR_SHADOW_RUNTIME_INVALID");
        }
    }

    public boolean metricsEquivalent(RapidOcrShadowCaseRecord other) {
        return other != null
                && caseId.equals(other.caseId)
                && caseIdentity.equals(other.caseIdentity)
                && partition == other.partition
                && domain.equals(other.domain)
                && difficulty == other.difficulty
                && failureSlices.equals(other.failureSlices)
                && diagnosticSlices.equals(other.diagnosticSlices)
                && ocr.equals(other.ocr)
                && layout.equals(other.layout)
                && order.equals(other.order)
                && repeat.equals(other.repeat)
                && confidence.equals(other.confidence)
                && observationCount == other.observationCount;
    }

    @Override
    public String toString() {
        return "RapidOcrShadowCaseRecord[recordVersion=" + recordVersion + ", caseId=" + caseId
                + ", caseIdentity=" + caseIdentity + ", partition=" + partition + ", domain=" + domain
                + ", difficulty=" + difficulty + ", observationCount=" + observationCount
                + ", acquisitionMicros=" + acquisitionMicros + ", payload=<redacted>]";
    }

    public record LineLayoutStats(
            LayeredEvaluationRecord.BinaryCounts lines,
            long centerContainedMatches,
            long predictedCoverageBpsSum,
            long goldCoverageBpsSum,
            long observedRegions
    ) {
        public LineLayoutStats {
            Objects.requireNonNull(lines, "lines");
            nonNegative(centerContainedMatches, predictedCoverageBpsSum, goldCoverageBpsSum, observedRegions);
            if (centerContainedMatches > lines.matched() || observedRegions > lines.expected()
                    || predictedCoverageBpsSum > Math.multiplyExact(10_000L, lines.matched())
                    || goldCoverageBpsSum > Math.multiplyExact(10_000L, lines.matched())) {
                throw invalid("RAPIDOCR_SHADOW_LAYOUT_INVALID");
            }
        }

        public int meanPredictedCoverageBps() {
            return lines.matched() == 0 ? 0
                    : Math.toIntExact(Math.floorDiv(predictedCoverageBpsSum, lines.matched()));
        }

        public int meanGoldCoverageBps() {
            return lines.matched() == 0 ? 0
                    : Math.toIntExact(Math.floorDiv(goldCoverageBpsSum, lines.matched()));
        }
    }

    public record ReadingOrderStats(
            long expectedEdges,
            long comparableEdges,
            long correctEdges,
            boolean allReferencedRegionsObserved
    ) {
        public ReadingOrderStats {
            nonNegative(expectedEdges, comparableEdges, correctEdges);
            if (correctEdges > comparableEdges || comparableEdges > expectedEdges) {
                throw invalid("RAPIDOCR_SHADOW_ORDER_INVALID");
            }
        }

        public long errors() {
            return comparableEdges - correctEdges;
        }

        public int accuracyBps() {
            return comparableEdges == 0 ? 10_000
                    : Math.toIntExact(Math.floorDiv(correctEdges * 10_000L, comparableEdges));
        }
    }

    public record RepeatObservabilityStats(
            long expectedGroups,
            long completeGroups,
            long expectedItems,
            long completeItems,
            long expectedMemberships,
            long observableMemberships
    ) {
        public RepeatObservabilityStats {
            nonNegative(expectedGroups, completeGroups, expectedItems, completeItems,
                    expectedMemberships, observableMemberships);
            if (completeGroups > expectedGroups || completeItems > expectedItems
                    || observableMemberships > expectedMemberships) {
                throw invalid("RAPIDOCR_SHADOW_REPEAT_INVALID");
            }
        }

        public int membershipRecallBps() {
            return expectedMemberships == 0 ? 10_000
                    : Math.toIntExact(Math.floorDiv(observableMemberships * 10_000L, expectedMemberships));
        }
    }

    public record ConfidenceStats(
            long observations,
            long nativeValueBpsSum,
            long lowCount,
            long mediumCount,
            long highCount
    ) {
        public ConfidenceStats {
            nonNegative(observations, nativeValueBpsSum, lowCount, mediumCount, highCount);
            if (lowCount + mediumCount + highCount != observations
                    || nativeValueBpsSum > Math.multiplyExact(10_000L, observations)) {
                throw invalid("RAPIDOCR_SHADOW_CONFIDENCE_INVALID");
            }
        }

        public int meanNativeValueBps() {
            return observations == 0 ? 0
                    : Math.toIntExact(Math.floorDiv(nativeValueBpsSum, observations));
        }
    }

    public enum DiagnosticSlice { DENSE_TEXT, SMALL_TEXT }

    private static void nonNegative(long... values) {
        for (var value : values) if (value < 0) throw invalid("RAPIDOCR_SHADOW_NEGATIVE_STAT");
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
