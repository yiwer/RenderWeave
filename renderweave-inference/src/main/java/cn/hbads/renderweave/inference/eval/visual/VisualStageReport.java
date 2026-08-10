package cn.hbads.renderweave.inference.eval.visual;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete or partial payload-free report over the fixed visual stage corpus. */
public record VisualStageReport(
        String reportVersion,
        String corpusVersion,
        String corpusSourceSha256,
        int expectedCaseCount,
        int observedCaseCount,
        boolean complete,
        List<String> missingCaseIds,
        Aggregate global,
        Map<String, Aggregate> partitions,
        Map<String, Aggregate> styles,
        Map<String, Aggregate> domainPacks
) {
    public static final String VERSION = "renderweave-visual-stage-report/1.0";

    public VisualStageReport {
        if (!VERSION.equals(reportVersion) || !VisualStageCorpus.VERSION.equals(corpusVersion)
                || corpusSourceSha256 == null || !corpusSourceSha256.matches("[0-9a-f]{64}")
                || expectedCaseCount != 60 || observedCaseCount < 0 || observedCaseCount > expectedCaseCount
                || complete != (observedCaseCount == expectedCaseCount)) {
            throw new IllegalArgumentException("Visual stage report envelope is invalid");
        }
        missingCaseIds = List.copyOf(Objects.requireNonNull(missingCaseIds, "missingCaseIds"));
        Objects.requireNonNull(global, "global");
        partitions = Map.copyOf(Objects.requireNonNull(partitions, "partitions"));
        styles = Map.copyOf(Objects.requireNonNull(styles, "styles"));
        domainPacks = Map.copyOf(Objects.requireNonNull(domainPacks, "domainPacks"));
        if (missingCaseIds.size() != expectedCaseCount - observedCaseCount
                || global.caseCount() != observedCaseCount) {
            throw new IllegalArgumentException("Visual stage report case accounting is invalid");
        }
    }

    public record Aggregate(
            int caseCount,
            int passedCandidateCases,
            int providerCalls,
            int repairAttemptedCases,
            int repairSuccessfulCases,
            VisualStageEvaluationResult.StageCounts slots,
            VisualStageEvaluationResult.StageCounts groups,
            VisualStageEvaluationResult.GroundingMetrics grounding,
            VisualStageEvaluationResult.StageCounts entities,
            VisualStageEvaluationResult.StageCounts relationships,
            VisualStageEvaluationResult.StageCounts bindings,
            SurvivalAggregate survival,
            long treeEditDistance,
            long treeEditDenominator,
            List<VisualStageEvaluationResult.CalibrationBin> calibrationBins,
            FinalCandidateAggregate finalCandidate
    ) {
        public Aggregate {
            if (caseCount < 0 || passedCandidateCases < 0 || passedCandidateCases > caseCount
                    || providerCalls < 0 || repairAttemptedCases < 0 || repairAttemptedCases > caseCount
                    || repairSuccessfulCases < 0 || repairSuccessfulCases > repairAttemptedCases
                    || treeEditDistance < 0 || treeEditDenominator < 0
                    || treeEditDistance > treeEditDenominator) {
                throw new IllegalArgumentException("Visual stage aggregate scalar is invalid");
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
            if (calibrationBins.size() != 10 || finalCandidate.caseCount() != caseCount) {
                throw new IllegalArgumentException("Visual stage aggregate decomposition is invalid");
            }
        }

        public int elementPrecisionBps() {
            return VisualStageEvaluationResult.StageCounts.precision(
                    Math.addExact(slots.matched(), groups.matched()),
                    Math.addExact(slots.actual(), groups.actual()),
                    Math.addExact(slots.expected(), groups.expected())
            );
        }

        public int elementRecallBps() {
            return VisualStageEvaluationResult.StageCounts.recall(
                    Math.addExact(slots.matched(), groups.matched()),
                    Math.addExact(slots.expected(), groups.expected())
            );
        }

        public int elementF1Bps() {
            return VisualStageEvaluationResult.StageCounts.f1(
                    Math.addExact(slots.matched(), groups.matched()),
                    Math.addExact(slots.expected(), groups.expected()),
                    Math.addExact(slots.actual(), groups.actual())
            );
        }

        public int normalizedTreeSimilarityBps() {
            return treeEditDenominator == 0 ? 10_000
                    : 10_000 - VisualStageEvaluationResult.ratio(treeEditDistance, treeEditDenominator);
        }

        public int repairYieldBps() {
            return repairAttemptedCases == 0 ? 10_000
                    : VisualStageEvaluationResult.ratio(repairSuccessfulCases, repairAttemptedCases);
        }

        public int expectedCalibrationErrorBps() {
            var total = calibrationBins.stream().mapToLong(VisualStageEvaluationResult.CalibrationBin::count).sum();
            if (total == 0) return 0;
            long weighted = 0;
            for (var bin : calibrationBins) {
                if (bin.count() == 0) continue;
                var confidence = Math.floorDiv(bin.confidenceBpsSum(), bin.count());
                var accuracy = VisualStageEvaluationResult.ratio(bin.correct(), bin.count());
                weighted = Math.addExact(weighted,
                        Math.multiplyExact((long) bin.count(), Math.abs(confidence - accuracy)));
            }
            return (int) Math.floorDiv(weighted, total);
        }

        public int brierScoreBps() {
            var total = calibrationBins.stream().mapToLong(VisualStageEvaluationResult.CalibrationBin::count).sum();
            return total == 0 ? 0 : (int) Math.floorDiv(
                    calibrationBins.stream().mapToLong(
                            VisualStageEvaluationResult.CalibrationBin::squaredErrorBpsSum).sum(), total
            );
        }
    }

    public record SurvivalAggregate(
            int expectedSlots,
            int observedSlots,
            int correctlyBoundSlots,
            int candidateSlots
    ) {
        public SurvivalAggregate {
            if (expectedSlots < 0 || observedSlots < 0 || observedSlots > expectedSlots
                    || correctlyBoundSlots < 0 || correctlyBoundSlots > observedSlots
                    || candidateSlots < 0 || candidateSlots > correctlyBoundSlots) {
                throw new IllegalArgumentException("Visual stage survival aggregate is invalid");
            }
        }

        public int candidateSurvivalBps() {
            return expectedSlots == 0 ? 10_000
                    : VisualStageEvaluationResult.ratio(candidateSlots, expectedSlots);
        }
    }

    public record FinalCandidateAggregate(
            int caseCount,
            int passedCases,
            long bundleContractBpsSum,
            VisualStageEvaluationResult.StageCounts entities,
            VisualStageEvaluationResult.StageCounts fields,
            VisualStageEvaluationResult.StageCounts relationships,
            int supportedTypeExpected,
            int supportedTypeMatched,
            int evidenceExpected,
            int evidencePresent,
            long dagValidityBpsSum,
            int criticalHallucinations,
            int blockers
    ) {
        public FinalCandidateAggregate {
            if (caseCount < 0 || passedCases < 0 || passedCases > caseCount
                    || bundleContractBpsSum < 0 || bundleContractBpsSum > Math.multiplyExact(10_000L, caseCount)
                    || supportedTypeExpected < 0 || supportedTypeMatched < 0
                    || supportedTypeMatched > supportedTypeExpected
                    || evidenceExpected < 0 || evidencePresent < 0 || evidencePresent > evidenceExpected
                    || dagValidityBpsSum < 0 || dagValidityBpsSum > Math.multiplyExact(10_000L, caseCount)
                    || criticalHallucinations < 0 || blockers < 0) {
                throw new IllegalArgumentException("Final Candidate aggregate is invalid");
            }
            Objects.requireNonNull(entities, "entities");
            Objects.requireNonNull(fields, "fields");
            Objects.requireNonNull(relationships, "relationships");
        }

        public int averageBundleContractBps() {
            return caseCount == 0 ? 10_000 : (int) Math.floorDiv(bundleContractBpsSum, caseCount);
        }

        public int averageDagValidityBps() {
            return caseCount == 0 ? 10_000 : (int) Math.floorDiv(dagValidityBpsSum, caseCount);
        }

        public int supportedTypeAccuracyBps() {
            return supportedTypeExpected == 0 ? 10_000
                    : VisualStageEvaluationResult.ratio(supportedTypeMatched, supportedTypeExpected);
        }

        public int evidenceCoverageBps() {
            return evidenceExpected == 0 ? 10_000
                    : VisualStageEvaluationResult.ratio(evidencePresent, evidenceExpected);
        }
    }
}
