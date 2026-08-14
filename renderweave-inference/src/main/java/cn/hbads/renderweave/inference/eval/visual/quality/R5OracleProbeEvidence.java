package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredEvaluationRecord;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Payload-safe decision record for the fixed local-only R5 oracle differential. */
public record R5OracleProbeEvidence(
        String contractVersion,
        String protocolIdentity,
        String assignmentIdentity,
        String transformIdentity,
        String evaluationIdentity,
        String corpusIdentity,
        String annotationSetIdentity,
        String capabilityIdentity,
        String acquisitionPolicyIdentity,
        int runs,
        int devCases,
        int holdoutCases,
        int actualAcquisitions,
        int deterministicCases,
        List<CaseDifferential> cases,
        Map<Predicate, PredicateResult> predicates,
        Disposition disposition,
        boolean triggered,
        String reasonCode,
        ExternalProviderUsage externalProviderUsage
) {
    public static final String VERSION = "renderweave-r5-oracle-probe/1.0";

    public R5OracleProbeEvidence {
        if (!VERSION.equals(contractVersion)) throw invalid("R5_PROBE_VERSION_INVALID");
        requireIdentity(protocolIdentity, "R5_PROBE_PROTOCOL_IDENTITY_INVALID");
        requireIdentity(assignmentIdentity, "R5_PROBE_ASSIGNMENT_IDENTITY_INVALID");
        requireIdentity(transformIdentity, "R5_PROBE_TRANSFORM_IDENTITY_INVALID");
        requireIdentity(evaluationIdentity, "R5_PROBE_EVALUATION_IDENTITY_INVALID");
        requireIdentity(corpusIdentity, "R5_PROBE_CORPUS_IDENTITY_INVALID");
        requireIdentity(annotationSetIdentity, "R5_PROBE_ANNOTATION_IDENTITY_INVALID");
        if (capabilityIdentity == null || capabilityIdentity.isBlank()) {
            throw invalid("R5_PROBE_CAPABILITY_IDENTITY_INVALID");
        }
        requireIdentity(acquisitionPolicyIdentity, "R5_PROBE_POLICY_IDENTITY_INVALID");
        if (runs != 2 || devCases != 3 || holdoutCases != 1 || actualAcquisitions != 16
                || deterministicCases < 0 || deterministicCases > 4) {
            throw invalid("R5_PROBE_ACCOUNTING_INVALID");
        }
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        if (cases.size() != 4 || new HashSet<>(cases.stream().map(CaseDifferential::caseId).toList()).size() != 4
                || cases.stream().filter(item -> item.partition() == LayeredEvaluationRecord.Partition.DEV).count() != 3
                || cases.stream().filter(item -> item.partition() == LayeredEvaluationRecord.Partition.HOLDOUT).count() != 1
                || cases.stream().filter(CaseDifferential::deterministic).count() != deterministicCases) {
            throw invalid("R5_PROBE_CASE_SET_INVALID");
        }
        predicates = canonicalPredicates(predicates);
        Objects.requireNonNull(disposition, "disposition");
        if (triggered != (disposition == Disposition.TRIGGERED)
                || triggered && predicates.values().stream().anyMatch(result -> result != PredicateResult.PASS)) {
            throw invalid("R5_PROBE_DISPOSITION_INVALID");
        }
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw invalid("R5_PROBE_REASON_INVALID");
        }
        externalProviderUsage = Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
        if (!externalProviderUsage.zeroUsage()) throw invalid("R5_PROBE_PROVIDER_USAGE_NONZERO");
    }

    public static R5OracleProbeEvidence decide(
            String evaluationIdentity,
            List<CaseDifferential> source,
            int deterministicCases
    ) {
        source = List.copyOf(Objects.requireNonNull(source, "source"));
        var protocol = OfflineQualityEvaluationProtocol.load();
        var assignment = protocol.r5ProbeAssignment();
        var corpus = new LayeredVisualCorpus();
        if (!source.stream().map(CaseDifferential::caseId).toList().equals(assignment.caseIds())) {
            throw invalid("R5_PROBE_ASSIGNMENT_DRIFT");
        }
        for (var item : source) {
            var expected = corpus.require(item.caseId());
            if (!expected.caseIdentity().equals(item.caseIdentity())
                    || expected.partition() != item.partition()
                    || expected.renderCase().width() != item.sourceWidth()
                    || expected.renderCase().height() != item.sourceHeight()) {
                throw invalid("R5_PROBE_CASE_IDENTITY_DRIFT");
            }
        }

        var deterministic = deterministicCases == 4 && source.stream().allMatch(CaseDifferential::deterministic);
        var unreadable = source.stream().allMatch(item ->
                item.baseline().matchedLines() < item.baseline().expectedLines());
        var improved = source.stream().allMatch(CaseDifferential::targetImproved);
        var hallucinationSafe = source.stream().allMatch(item ->
                item.oracle().hallucinationCases() <= item.baseline().hallucinationCases());
        var causal = deterministic && unreadable && improved && hallucinationSafe;

        var predicates = new EnumMap<Predicate, PredicateResult>(Predicate.class);
        predicates.put(Predicate.EXACT_ASSIGNMENT, PredicateResult.PASS);
        predicates.put(Predicate.TWO_RUN_DETERMINISM,
                deterministic ? PredicateResult.PASS : PredicateResult.MISSING);
        predicates.put(Predicate.REPOSITORY_ONLY_INPUT, PredicateResult.PASS);
        predicates.put(Predicate.FIXED_HIGHER_RESOLUTION_TRANSFORM, PredicateResult.PASS);
        predicates.put(Predicate.STATIC_VIEW_UNREADABLE,
                unreadable ? PredicateResult.PASS : PredicateResult.FAIL);
        predicates.put(Predicate.TARGET_SLICE_IMPROVED,
                improved ? PredicateResult.PASS : PredicateResult.FAIL);
        predicates.put(Predicate.CRITICAL_HALLUCINATION_NON_INCREASE,
                hallucinationSafe ? PredicateResult.PASS : PredicateResult.FAIL);
        predicates.put(Predicate.STATIC_VIEW_CAUSALITY,
                causal ? PredicateResult.PASS : deterministic ? PredicateResult.FAIL : PredicateResult.MISSING);

        var disposition = !deterministic
                ? Disposition.MISSING
                : causal ? Disposition.TRIGGERED : Disposition.NOT_TRIGGERED;
        var reason = !deterministic
                ? "R5_TWO_RUN_DETERMINISM_MISSING"
                : !unreadable
                ? "R5_STATIC_VIEW_UNREADABILITY_NOT_OBSERVED"
                : !improved
                ? "R5_ORACLE_IMPROVEMENT_NOT_PROVEN"
                : !hallucinationSafe
                ? "R5_CRITICAL_HALLUCINATION_REGRESSION"
                : "R5_ORACLE_DIFFERENTIAL_CONFIRMED";
        var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
        return new R5OracleProbeEvidence(
                VERSION,
                protocol.identity(),
                assignment.identity(),
                new R5OracleHigherResolutionTransform().identity(),
                evaluationIdentity,
                corpus.corpusIdentity(),
                corpus.annotationSetIdentity(),
                policy.capabilityIdentity(),
                "AcquisitionPolicy/1.0:" + policy.identity(),
                2,
                3,
                1,
                16,
                deterministicCases,
                source,
                predicates,
                disposition,
                causal,
                reason,
                new ExternalProviderUsage(0, 0, 0));
    }

    private static Map<Predicate, PredicateResult> canonicalPredicates(
            Map<Predicate, PredicateResult> source
    ) {
        var result = new EnumMap<Predicate, PredicateResult>(Predicate.class);
        result.putAll(Objects.requireNonNull(source, "predicates"));
        if (result.size() != Predicate.values().length
                || result.values().stream().anyMatch(Objects::isNull)) {
            throw invalid("R5_PROBE_PREDICATE_SET_INVALID");
        }
        return Map.copyOf(result);
    }

    private static void requireIdentity(String value, String code) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9._+/-]{0,127}:[0-9a-f]{64}")) {
            throw invalid(code);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public enum Predicate {
        EXACT_ASSIGNMENT,
        TWO_RUN_DETERMINISM,
        REPOSITORY_ONLY_INPUT,
        FIXED_HIGHER_RESOLUTION_TRANSFORM,
        STATIC_VIEW_UNREADABLE,
        TARGET_SLICE_IMPROVED,
        CRITICAL_HALLUCINATION_NON_INCREASE,
        STATIC_VIEW_CAUSALITY
    }

    public enum PredicateResult { PASS, FAIL, MISSING }

    public enum Disposition { TRIGGERED, NOT_TRIGGERED, MISSING }

    public record CaseDifferential(
            String caseId,
            String caseIdentity,
            LayeredEvaluationRecord.Partition partition,
            int sourceWidth,
            int sourceHeight,
            int oracleWidth,
            int oracleHeight,
            CaseMetrics baseline,
            CaseMetrics oracle,
            boolean deterministic
    ) {
        public CaseDifferential {
            if (caseId == null || !caseId.matches("[a-z][a-z0-9-]{0,127}")) {
                throw invalid("R5_PROBE_CASE_ID_INVALID");
            }
            requireIdentity(caseIdentity, "R5_PROBE_CASE_IDENTITY_INVALID");
            Objects.requireNonNull(partition, "partition");
            if (sourceWidth < 1 || sourceHeight < 1 || oracleWidth <= sourceWidth
                    || oracleHeight <= sourceHeight || oracleWidth > 2_400 || oracleHeight > 2_400) {
                throw invalid("R5_PROBE_CASE_DIMENSIONS_INVALID");
            }
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(oracle, "oracle");
        }

        public boolean targetImproved() {
            return oracle.matchedLines() > baseline.matchedLines()
                    || oracle.characterErrors() < baseline.characterErrors();
        }
    }

    public record CaseMetrics(
            long observationCount,
            long expectedLines,
            long matchedLines,
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
                    || characterErrors < 0 || hallucinationCases < 0
                    || expectedPrecedenceEdges < 0 || comparablePrecedenceEdges < 0
                    || correctPrecedenceEdges < 0 || correctPrecedenceEdges > comparablePrecedenceEdges
                    || comparablePrecedenceEdges > expectedPrecedenceEdges
                    || expectedRepeatMemberships < 0 || observableRepeatMemberships < 0
                    || observableRepeatMemberships > expectedRepeatMemberships) {
                throw invalid("R5_PROBE_CASE_METRICS_INVALID");
            }
        }
    }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) {
        public ExternalProviderUsage {
            if (attempts < 0 || reservations < 0 || costMicrosCny < 0) {
                throw invalid("R5_PROBE_PROVIDER_USAGE_INVALID");
            }
        }

        public boolean zeroUsage() {
            return attempts == 0 && reservations == 0 && costMicrosCny == 0;
        }
    }
}
