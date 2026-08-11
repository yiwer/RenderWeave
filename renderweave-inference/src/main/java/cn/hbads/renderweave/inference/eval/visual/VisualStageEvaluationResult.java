package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.eval.LiveEvaluationResult;

import java.util.List;
import java.util.Objects;

/** Payload-free per-case sufficient statistics for independent aggregation and replay. */
public record VisualStageEvaluationResult(
        String caseId,
        VisualStageCorpus.Partition partition,
        VisualStageCorpus.DomainPack domainPack,
        VisualStageCorpus.Style style,
        String outcomeCode,
        int providerCalls,
        int repairRounds,
        StageCounts slots,
        StageCounts groups,
        GroundingMetrics grounding,
        StageCounts entities,
        StageCounts relationships,
        StageCounts bindings,
        SurvivalMetrics survival,
        int treeEditDistance,
        int treeEditDenominator,
        List<CalibrationBin> calibrationBins,
        FinalCandidateMetrics finalCandidate
) {
    public VisualStageEvaluationResult {
        if (caseId == null || !caseId.matches("[a-z][a-z0-9-]{0,127}")
                || outcomeCode == null || !outcomeCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("Visual stage result identity is invalid");
        }
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(domainPack, "domainPack");
        Objects.requireNonNull(style, "style");
        if (providerCalls < 0 || providerCalls > 8 || repairRounds < 0 || repairRounds > 2
                || treeEditDistance < 0 || treeEditDenominator < 1
                || treeEditDistance > treeEditDenominator) {
            throw new IllegalArgumentException("Visual stage result scalar is invalid");
        }
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(grounding, "grounding");
        Objects.requireNonNull(entities, "entities");
        Objects.requireNonNull(relationships, "relationships");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(survival, "survival");
        calibrationBins = List.copyOf(Objects.requireNonNull(calibrationBins, "calibrationBins"));
        Objects.requireNonNull(finalCandidate, "finalCandidate");
        if (calibrationBins.size() != 10) {
            throw new IllegalArgumentException("Visual stage calibration requires ten fixed bins");
        }
        for (var index = 0; index < calibrationBins.size(); index++) {
            if (calibrationBins.get(index).binIndex() != index) {
                throw new IllegalArgumentException("Visual stage calibration bins are not canonical");
            }
        }
    }

    public int elementPrecisionBps() {
        return StageCounts.precision(slots.matched() + groups.matched(),
                slots.actual() + groups.actual(), slots.expected() + groups.expected());
    }

    public int elementRecallBps() {
        return StageCounts.recall(slots.matched() + groups.matched(),
                slots.expected() + groups.expected());
    }

    public int elementF1Bps() {
        return StageCounts.f1(slots.matched() + groups.matched(),
                slots.expected() + groups.expected(), slots.actual() + groups.actual());
    }

    public int normalizedTreeSimilarityBps() {
        return 10_000 - ratio(treeEditDistance, treeEditDenominator);
    }

    public int expectedCalibrationErrorBps() {
        var total = calibrationBins.stream().mapToLong(CalibrationBin::count).sum();
        if (total == 0) return 0;
        long weightedError = 0;
        for (var bin : calibrationBins) {
            if (bin.count() == 0) continue;
            var averageConfidence = Math.floorDiv(bin.confidenceBpsSum(), bin.count());
            var accuracy = ratio(bin.correct(), bin.count());
            weightedError = Math.addExact(weightedError,
                    Math.multiplyExact((long) bin.count(), Math.abs(averageConfidence - accuracy)));
        }
        return (int) Math.floorDiv(weightedError, total);
    }

    public int brierScoreBps() {
        var total = calibrationBins.stream().mapToLong(CalibrationBin::count).sum();
        if (total == 0) return 0;
        var sum = calibrationBins.stream().mapToLong(CalibrationBin::squaredErrorBpsSum).sum();
        return (int) Math.floorDiv(sum, total);
    }

    static int ratio(long numerator, long denominator) {
        if (denominator == 0) return 10_000;
        return (int) Math.floorDiv(Math.multiplyExact(numerator, 10_000L), denominator);
    }

    public record StageCounts(int expected, int actual, int matched) {
        public StageCounts {
            if (expected < 0 || actual < 0 || matched < 0 || matched > expected || matched > actual) {
                throw new IllegalArgumentException("Visual stage counts are invalid");
            }
        }

        public int precisionBps() { return precision(matched, actual, expected); }
        public int recallBps() { return recall(matched, expected); }
        public int f1Bps() { return f1(matched, expected, actual); }

        static int precision(long matched, long actual, long expected) {
            return actual == 0 ? expected == 0 ? 10_000 : 0 : ratio(matched, actual);
        }

        static int recall(long matched, long expected) {
            return expected == 0 ? 10_000 : ratio(matched, expected);
        }

        static int f1(long matched, long expected, long actual) {
            var denominator = Math.addExact(expected, actual);
            return denominator == 0 ? 10_000 : ratio(Math.multiplyExact(2L, matched), denominator);
        }
    }

    public record GroundingMetrics(
            int expected,
            int semanticallyMatched,
            int matchedAtIou50,
            long matchedIouBpsSum
    ) {
        public GroundingMetrics {
            if (expected < 0 || semanticallyMatched < 0 || semanticallyMatched > expected
                    || matchedAtIou50 < 0 || matchedAtIou50 > semanticallyMatched
                    || matchedIouBpsSum < 0 || matchedIouBpsSum > Math.multiplyExact(10_000L, semanticallyMatched)) {
                throw new IllegalArgumentException("Visual grounding metrics are invalid");
            }
        }

        public int recallAtIou50Bps() {
            return expected == 0 ? 10_000 : ratio(matchedAtIou50, expected);
        }

        public int meanMatchedIouBps() {
            return semanticallyMatched == 0 ? 0 : (int) Math.floorDiv(matchedIouBpsSum,
                    semanticallyMatched);
        }
    }

    public record SurvivalMetrics(
            int expectedSlots,
            int observedSlots,
            int correctlyBoundSlots,
            int candidateSlots
    ) {
        public SurvivalMetrics {
            if (expectedSlots < 0 || observedSlots < 0 || observedSlots > expectedSlots
                    || correctlyBoundSlots < 0 || correctlyBoundSlots > observedSlots
                    || candidateSlots < 0 || candidateSlots > correctlyBoundSlots) {
                throw new IllegalArgumentException("Visual stage survival metrics are invalid");
            }
        }

        public int observationSurvivalBps() {
            return expectedSlots == 0 ? 10_000 : ratio(observedSlots, expectedSlots);
        }

        public int bindingSurvivalBps() {
            return expectedSlots == 0 ? 10_000 : ratio(correctlyBoundSlots, expectedSlots);
        }

        public int candidateSurvivalBps() {
            return expectedSlots == 0 ? 10_000 : ratio(candidateSlots, expectedSlots);
        }
    }

    public record CalibrationBin(
            int binIndex,
            int count,
            int correct,
            long confidenceBpsSum,
            long squaredErrorBpsSum
    ) {
        public CalibrationBin {
            if (binIndex < 0 || binIndex > 9 || count < 0 || correct < 0 || correct > count
                    || confidenceBpsSum < 0 || confidenceBpsSum > Math.multiplyExact(10_000L, count)
                    || squaredErrorBpsSum < 0 || squaredErrorBpsSum > Math.multiplyExact(10_000L, count)) {
                throw new IllegalArgumentException("Visual calibration bin is invalid");
            }
        }
    }

    public record FinalCandidateMetrics(
            String outcomeCode,
            boolean passed,
            int bundleContractBps,
            StageCounts entities,
            StageCounts fields,
            StageCounts relationships,
            int supportedTypeExpected,
            int supportedTypeMatched,
            int evidenceExpected,
            int evidencePresent,
            int dagValidityBps,
            int criticalHallucinations,
            int blockers
    ) {
        public FinalCandidateMetrics {
            if (outcomeCode == null || outcomeCode.isBlank() || bundleContractBps < 0
                    || bundleContractBps > 10_000 || supportedTypeExpected < 0
                    || supportedTypeMatched < 0 || supportedTypeMatched > supportedTypeExpected
                    || evidenceExpected < 0 || evidencePresent < 0 || evidencePresent > evidenceExpected
                    || dagValidityBps < 0 || dagValidityBps > 10_000
                    || criticalHallucinations < 0 || blockers < 0) {
                throw new IllegalArgumentException("Final Candidate metrics are invalid");
            }
            Objects.requireNonNull(entities, "entities");
            Objects.requireNonNull(fields, "fields");
            Objects.requireNonNull(relationships, "relationships");
        }

        public static FinalCandidateMetrics from(LiveEvaluationResult value) {
            Objects.requireNonNull(value, "value");
            return new FinalCandidateMetrics(
                    value.outcomeCode(), value.passed(), value.bundleContractBps(),
                    new StageCounts(value.expectedEntityCount(), value.actualEntityCount(),
                            value.matchedEntityCount()),
                    new StageCounts(value.expectedFieldCount(), value.actualFieldCount(),
                            value.matchedFieldCount()),
                    new StageCounts(value.expectedEdgeCount(), value.actualEdgeCount(),
                            value.matchedEdgeCount()),
                    value.supportedTypeExpectedCount(), value.supportedTypeMatchedCount(),
                    value.evidenceExpectedCount(), value.evidencePresentCount(), value.dagValidityBps(),
                    value.criticalHallucinationCount(), value.blockerCount()
            );
        }
    }
}
