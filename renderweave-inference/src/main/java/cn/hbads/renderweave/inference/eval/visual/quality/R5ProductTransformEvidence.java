package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredEvaluationRecord;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Payload-safe reader and threshold assessor for the closed product-transform experiment. */
public record R5ProductTransformEvidence(
        String contractVersion,
        String assignmentIdentity,
        String transformIdentity,
        String evaluationIdentity,
        String corpusIdentity,
        String annotationSetIdentity,
        String capabilityIdentity,
        String acquisitionPolicyIdentity,
        int runsCompleted,
        int caseCount,
        int devCases,
        int holdoutCases,
        int actualAcquisitions,
        int deterministicCases,
        List<RunRecord> runs,
        Map<Predicate, PredicateResult> predicates,
        int aggregateStaticLineRecallBps,
        int aggregateInspectedLineRecallBps,
        int aggregateLineRecallGainBps,
        long aggregateStaticCharacterErrors,
        long aggregateInspectedCharacterErrors,
        Disposition disposition,
        boolean qualified,
        String reasonCode,
        ExternalProviderUsage externalProviderUsage
) {
    public static final String VERSION = "renderweave-r5-product-transform-evidence/1.0";
    public static final String TRANSFORM_IDENTITY = "renderweave-r5-product-raster-transform/1.0";

    public R5ProductTransformEvidence {
        if (!VERSION.equals(contractVersion)) throw invalid("R5_PRODUCT_EVIDENCE_VERSION_INVALID");
        requireIdentity(assignmentIdentity, "R5_PRODUCT_ASSIGNMENT_IDENTITY_INVALID");
        if (!TRANSFORM_IDENTITY.equals(transformIdentity)) throw invalid("R5_PRODUCT_TRANSFORM_IDENTITY_INVALID");
        requireIdentity(evaluationIdentity, "R5_PRODUCT_EVALUATION_IDENTITY_INVALID");
        requireIdentity(corpusIdentity, "R5_PRODUCT_CORPUS_IDENTITY_INVALID");
        requireIdentity(annotationSetIdentity, "R5_PRODUCT_ANNOTATION_IDENTITY_INVALID");
        if (capabilityIdentity == null || capabilityIdentity.isBlank()) {
            throw invalid("R5_PRODUCT_CAPABILITY_IDENTITY_INVALID");
        }
        requireIdentity(acquisitionPolicyIdentity, "R5_PRODUCT_POLICY_IDENTITY_INVALID");
        if (runsCompleted != 2 || caseCount != 4 || devCases != 3 || holdoutCases != 1
                || actualAcquisitions != 16 || deterministicCases < 0 || deterministicCases > 4) {
            throw invalid("R5_PRODUCT_ACCOUNTING_INVALID");
        }
        runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
        if (runs.size() != 2 || runs.get(0).runOrdinal() != 1 || runs.get(1).runOrdinal() != 2) {
            throw invalid("R5_PRODUCT_RUN_SET_INVALID");
        }
        predicates = canonicalPredicates(predicates);
        if (aggregateStaticLineRecallBps < 0 || aggregateStaticLineRecallBps > 10_000
                || aggregateInspectedLineRecallBps < 0 || aggregateInspectedLineRecallBps > 10_000
                || aggregateLineRecallGainBps != aggregateInspectedLineRecallBps - aggregateStaticLineRecallBps
                || aggregateStaticCharacterErrors < 0 || aggregateInspectedCharacterErrors < 0) {
            throw invalid("R5_PRODUCT_AGGREGATE_INVALID");
        }
        Objects.requireNonNull(disposition, "disposition");
        if (qualified != (disposition == Disposition.QUALIFIED)
                || qualified && predicates.values().stream().anyMatch(value -> value != PredicateResult.PASS)
                || reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw invalid("R5_PRODUCT_DISPOSITION_INVALID");
        }
        externalProviderUsage = Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
        if (!externalProviderUsage.zeroUsage()) throw invalid("R5_PRODUCT_PROVIDER_USAGE_NONZERO");
    }

    static R5ProductTransformEvidence decide(
            String evaluationIdentity,
            List<RunRecord> sourceRuns,
            int deterministicCases
    ) {
        sourceRuns = List.copyOf(Objects.requireNonNull(sourceRuns, "sourceRuns"));
        var assignment = R5ProductTransformAssignment.load();
        var corpus = new LayeredVisualCorpus();
        if (sourceRuns.size() != 2 || sourceRuns.get(0).runOrdinal() != 1 || sourceRuns.get(1).runOrdinal() != 2) {
            throw invalid("R5_PRODUCT_RUN_SET_INVALID");
        }
        var expectedIds = assignment.cases().stream().map(R5ProductTransformAssignment.CaseAssignment::caseId).toList();
        for (var run : sourceRuns) {
            if (!run.cases().stream().map(CaseRecord::caseId).toList().equals(expectedIds)) {
                throw invalid("R5_PRODUCT_ASSIGNMENT_DRIFT");
            }
            for (var item : run.cases()) {
                var expected = corpus.require(item.caseId());
                if (!expected.caseIdentity().equals(item.caseIdentity()) || expected.partition() != item.partition()
                        || expected.renderCase().width() != item.sourceWidth()
                        || expected.renderCase().height() != item.sourceHeight()) {
                    throw invalid("R5_PRODUCT_CASE_IDENTITY_DRIFT");
                }
            }
        }

        var first = sourceRuns.getFirst().cases();
        var secondById = sourceRuns.get(1).cases().stream().collect(java.util.stream.Collectors.toMap(
                CaseRecord::caseId, item -> item));
        var determinism = deterministicCases == 4 && first.stream().allMatch(item ->
                item.deterministicEquivalent(secondById.get(item.caseId())));
        var perCaseImproved = first.stream().allMatch(CaseRecord::targetImproved);
        var hallucinationSafe = first.stream().allMatch(item ->
                item.inspected().hallucinationCases() <= item.staticView().hallucinationCases());
        var expectedLines = first.stream().mapToLong(item -> item.staticView().expectedLines()).sum();
        var staticMatched = first.stream().mapToLong(item -> item.staticView().matchedLines()).sum();
        var inspectedMatched = first.stream().mapToLong(item -> item.inspected().matchedLines()).sum();
        var staticRecall = ratio(staticMatched, expectedLines);
        var inspectedRecall = ratio(inspectedMatched, expectedLines);
        var recallGain = inspectedRecall - staticRecall;
        var staticErrors = first.stream().mapToLong(item -> item.staticView().characterErrors()).sum();
        var inspectedErrors = first.stream().mapToLong(item -> item.inspected().characterErrors()).sum();
        // The historical run did not independently prove either prerequisite. Keep the measured
        // threshold facts available for audit, but never turn this producer report into admission.
        var normalizedRasterProven = false;
        var providerZeroIndependentlyGrounded = false;
        var measuredThresholdsPass = determinism && perCaseImproved && hallucinationSafe
                && recallGain >= 500 && inspectedErrors < staticErrors;
        var qualified = normalizedRasterProven && providerZeroIndependentlyGrounded && measuredThresholdsPass;

        var predicates = new EnumMap<Predicate, PredicateResult>(Predicate.class);
        predicates.put(Predicate.EXACT_FROZEN_ASSIGNMENT, PredicateResult.PASS);
        predicates.put(Predicate.NORMALIZED_RASTER_ONLY, PredicateResult.FAIL);
        predicates.put(Predicate.TWO_RUN_DETERMINISM, determinism ? PredicateResult.PASS : PredicateResult.FAIL);
        predicates.put(Predicate.PER_CASE_TARGET_IMPROVEMENT,
                perCaseImproved ? PredicateResult.PASS : PredicateResult.FAIL);
        predicates.put(Predicate.AGGREGATE_LINE_RECALL_GAIN_0500_BPS,
                recallGain >= 500 ? PredicateResult.PASS : PredicateResult.FAIL);
        predicates.put(Predicate.AGGREGATE_CHARACTER_ERROR_REDUCTION,
                inspectedErrors < staticErrors ? PredicateResult.PASS : PredicateResult.FAIL);
        predicates.put(Predicate.PER_CASE_HALLUCINATION_NON_INCREASE,
                hallucinationSafe ? PredicateResult.PASS : PredicateResult.FAIL);
        predicates.put(Predicate.EXTERNAL_PROVIDER_ZERO, PredicateResult.FAIL);

        var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
        return new R5ProductTransformEvidence(
                VERSION, assignment.identity(), TRANSFORM_IDENTITY, evaluationIdentity,
                corpus.corpusIdentity(), corpus.annotationSetIdentity(), policy.capabilityIdentity(),
                "AcquisitionPolicy/1.0:" + policy.identity(), 2, 4, 3, 1, 16,
                deterministicCases, sourceRuns, predicates, staticRecall, inspectedRecall, recallGain,
                staticErrors, inspectedErrors,
                qualified ? Disposition.QUALIFIED : Disposition.NOT_QUALIFIED,
                qualified,
                qualified ? "R5_PRODUCT_TRANSFORM_QUALIFIED" : "R5_PRODUCT_TRANSFORM_NOT_QUALIFIED",
                new ExternalProviderUsage(0, 0, 0));
    }

    private static int ratio(long numerator, long denominator) {
        return denominator == 0 ? 10_000 : Math.toIntExact(Math.floorDiv(
                Math.multiplyExact(numerator, 10_000L), denominator));
    }

    private static Map<Predicate, PredicateResult> canonicalPredicates(Map<Predicate, PredicateResult> source) {
        var result = new EnumMap<Predicate, PredicateResult>(Predicate.class);
        result.putAll(Objects.requireNonNull(source, "predicates"));
        if (result.size() != Predicate.values().length || result.values().stream().anyMatch(Objects::isNull)) {
            throw invalid("R5_PRODUCT_PREDICATE_SET_INVALID");
        }
        return Map.copyOf(result);
    }

    private static void requireIdentity(String value, String code) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9._+/-]{0,127}:[0-9a-f]{64}")) {
            throw invalid(code);
        }
    }

    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }

    public enum Predicate {
        EXACT_FROZEN_ASSIGNMENT,
        NORMALIZED_RASTER_ONLY,
        TWO_RUN_DETERMINISM,
        PER_CASE_TARGET_IMPROVEMENT,
        AGGREGATE_LINE_RECALL_GAIN_0500_BPS,
        AGGREGATE_CHARACTER_ERROR_REDUCTION,
        PER_CASE_HALLUCINATION_NON_INCREASE,
        EXTERNAL_PROVIDER_ZERO
    }

    public enum PredicateResult { PASS, FAIL }

    public enum Disposition { QUALIFIED, NOT_QUALIFIED }

    public record RunRecord(int runOrdinal, List<CaseRecord> cases) {
        public RunRecord {
            if (runOrdinal < 1 || runOrdinal > 2) throw invalid("R5_PRODUCT_RUN_ORDINAL_INVALID");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            if (cases.size() != 4 || new HashSet<>(cases.stream().map(CaseRecord::caseId).toList()).size() != 4) {
                throw invalid("R5_PRODUCT_RUN_CASES_INVALID");
            }
        }
    }

    public record CaseRecord(
            String caseId,
            String caseIdentity,
            LayeredEvaluationRecord.Partition partition,
            int sourceWidth,
            int sourceHeight,
            String staticPlanIdentity,
            String requestIdentity,
            String inspectedPlanIdentity,
            int staticViewCount,
            int inspectedViewCount,
            long staticDecodedPixels,
            long inspectedDecodedPixels,
            long staticEncodedBytes,
            long inspectedEncodedBytes,
            long staticAcquisitionMicros,
            long inspectedAcquisitionMicros,
            ViewResource staticResource,
            List<ViewResource> inspectedResources,
            CaseMetrics staticView,
            CaseMetrics inspected
    ) {
        public CaseRecord {
            if (caseId == null || !caseId.matches("[a-z][a-z0-9-]{0,127}")) {
                throw invalid("R5_PRODUCT_CASE_ID_INVALID");
            }
            requireIdentity(caseIdentity, "R5_PRODUCT_CASE_IDENTITY_INVALID");
            Objects.requireNonNull(partition, "partition");
            if (sourceWidth < 1 || sourceHeight < 1 || staticViewCount != 1 || inspectedViewCount != 2
                    || staticDecodedPixels < 1 || inspectedDecodedPixels < 1
                    || inspectedDecodedPixels > 11_520_000L || staticEncodedBytes < 1
                    || inspectedEncodedBytes < 1
                    || staticEncodedBytes > 30L * 1024L * 1024L
                    || inspectedEncodedBytes > 30L * 1024L * 1024L
                    || exceedsCombinedEncodedByteLimit(staticEncodedBytes, inspectedEncodedBytes)
                    || staticAcquisitionMicros < 0 || inspectedAcquisitionMicros < 0) {
                throw invalid("R5_PRODUCT_CASE_RESOURCES_INVALID");
            }
            requireIdentity(staticPlanIdentity, "R5_PRODUCT_STATIC_PLAN_IDENTITY_INVALID");
            requireIdentity(requestIdentity, "R5_PRODUCT_REQUEST_IDENTITY_INVALID");
            requireIdentity(inspectedPlanIdentity, "R5_PRODUCT_INSPECTED_PLAN_IDENTITY_INVALID");
            staticResource = Objects.requireNonNull(staticResource, "staticResource");
            inspectedResources = List.copyOf(Objects.requireNonNull(inspectedResources, "inspectedResources"));
            if (inspectedResources.size() != inspectedViewCount
                    || new HashSet<>(inspectedResources.stream().map(ViewResource::identity).toList()).size()
                    != inspectedResources.size()
                    || staticDecodedPixels != staticResource.decodedPixels()
                    || staticEncodedBytes != staticResource.encodedBytes()
                    || inspectedDecodedPixels != checkedSum(
                    inspectedResources.stream().map(ViewResource::decodedPixels).toList())
                    || inspectedEncodedBytes != checkedSum(
                    inspectedResources.stream().map(ViewResource::encodedBytes).toList())) {
                throw invalid("R5_PRODUCT_VIEW_RESOURCE_DRIFT");
            }
            Objects.requireNonNull(staticView, "staticView");
            Objects.requireNonNull(inspected, "inspected");
            if (staticView.expectedLines() != inspected.expectedLines()) {
                throw invalid("R5_PRODUCT_GOLD_DENOMINATOR_DRIFT");
            }
        }

        public boolean targetImproved() {
            return inspected.matchedLines() > staticView.matchedLines()
                    || inspected.characterErrors() < staticView.characterErrors();
        }

        boolean deterministicEquivalent(CaseRecord other) {
            return other != null && caseId.equals(other.caseId()) && caseIdentity.equals(other.caseIdentity())
                    && partition == other.partition && sourceWidth == other.sourceWidth && sourceHeight == other.sourceHeight
                    && staticPlanIdentity.equals(other.staticPlanIdentity)
                    && requestIdentity.equals(other.requestIdentity)
                    && inspectedPlanIdentity.equals(other.inspectedPlanIdentity)
                    && staticViewCount == other.staticViewCount && inspectedViewCount == other.inspectedViewCount
                    && staticDecodedPixels == other.staticDecodedPixels
                    && inspectedDecodedPixels == other.inspectedDecodedPixels
                    && staticEncodedBytes == other.staticEncodedBytes
                    && inspectedEncodedBytes == other.inspectedEncodedBytes
                    && staticResource.equals(other.staticResource)
                    && inspectedResources.equals(other.inspectedResources)
                    && staticView.equals(other.staticView) && inspected.equals(other.inspected);
        }

        private static boolean exceedsCombinedEncodedByteLimit(long staticBytes, long inspectedBytes) {
            try {
                return Math.addExact(staticBytes, inspectedBytes) > 30L * 1024L * 1024L;
            } catch (ArithmeticException overflow) {
                return true;
            }
        }

        private static long checkedSum(List<Long> values) {
            try {
                var result = 0L;
                for (var value : values) result = Math.addExact(result, value);
                return result;
            } catch (ArithmeticException overflow) {
                throw invalid("R5_PRODUCT_VIEW_RESOURCE_OVERFLOW");
            }
        }
    }

    public record ViewResource(
            String identity,
            String artifactId,
            int width,
            int height,
            long encodedBytes
    ) {
        public ViewResource {
            requireIdentity(identity, "R5_PRODUCT_VIEW_IDENTITY_INVALID");
            if (artifactId == null || !artifactId.matches("[0-9a-f]{64}")
                    || width < 1 || height < 1 || width > 2_400 || height > 2_400
                    || encodedBytes < 1 || encodedBytes > 30L * 1024L * 1024L) {
                throw invalid("R5_PRODUCT_VIEW_RESOURCE_INVALID");
            }
        }

        public long decodedPixels() { return Math.multiplyExact((long) width, height); }
    }

    public record CaseMetrics(
            long observationCount,
            long expectedLines,
            long matchedLines,
            long predictedCharacters,
            long characterErrors,
            long hallucinationCases,
            long expectedPrecedenceEdges,
            long comparablePrecedenceEdges,
            long correctPrecedenceEdges,
            long expectedRepeatMemberships,
            long observableRepeatMemberships
    ) {
        public CaseMetrics {
            if (observationCount < 0 || expectedLines < 0 || matchedLines < 0 || matchedLines > expectedLines
                    || predictedCharacters < 0 || characterErrors < 0 || hallucinationCases < 0
                    || expectedPrecedenceEdges < 0 || comparablePrecedenceEdges < 0
                    || correctPrecedenceEdges < 0 || correctPrecedenceEdges > comparablePrecedenceEdges
                    || comparablePrecedenceEdges > expectedPrecedenceEdges
                    || expectedRepeatMemberships < 0 || observableRepeatMemberships < 0
                    || observableRepeatMemberships > expectedRepeatMemberships) {
                throw invalid("R5_PRODUCT_CASE_METRICS_INVALID");
            }
        }
    }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) {
        public ExternalProviderUsage {
            if (attempts < 0 || reservations < 0 || costMicrosCny < 0) {
                throw invalid("R5_PRODUCT_PROVIDER_USAGE_INVALID");
            }
        }

        public boolean zeroUsage() { return attempts == 0 && reservations == 0 && costMicrosCny == 0; }
    }
}
