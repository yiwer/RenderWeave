package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredEvaluationRecord;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowCaseEvaluator;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowCaseRecord;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluationIdentity;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowReport;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowReportJsonCodec;
import cn.hbads.renderweave.inference.vision.DocumentObservationCompatibilityProjection;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Payload-safe causal probe for the evidence-only R3 order/repeat route. */
public record R3OrderRepeatProbeEvidence(
        String contractVersion,
        String protocolIdentity,
        String assignmentIdentity,
        String sourceEvaluationIdentity,
        String sourceReportIdentity,
        int runs,
        int devCases,
        int holdoutCases,
        List<CaseEvidence> cases,
        Map<Predicate, PredicateResult> predicates,
        Disposition disposition,
        boolean triggered,
        String reasonCode,
        ExternalProviderUsage externalProviderUsage
) {
    public static final String VERSION = "renderweave-r3-order-repeat-probe/1.0";

    public R3OrderRepeatProbeEvidence {
        if (!VERSION.equals(contractVersion)) throw invalid("R3_PROBE_VERSION_INVALID");
        requireIdentity(protocolIdentity, "R3_PROBE_PROTOCOL_IDENTITY_INVALID");
        requireIdentity(assignmentIdentity, "R3_PROBE_ASSIGNMENT_IDENTITY_INVALID");
        requireIdentity(sourceEvaluationIdentity, "R3_PROBE_EVALUATION_IDENTITY_INVALID");
        requireIdentity(sourceReportIdentity, "R3_PROBE_REPORT_IDENTITY_INVALID");
        if (runs != 2 || devCases != 3 || holdoutCases != 1) {
            throw invalid("R3_PROBE_ACCOUNTING_INVALID");
        }
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        if (cases.size() != 4 || new HashSet<>(cases.stream().map(CaseEvidence::caseId).toList()).size() != 4
                || cases.stream().filter(item -> item.partition() == LayeredEvaluationRecord.Partition.DEV).count() != 3
                || cases.stream().filter(item -> item.partition() == LayeredEvaluationRecord.Partition.HOLDOUT).count() != 1) {
            throw invalid("R3_PROBE_CASE_SET_INVALID");
        }
        predicates = canonicalPredicates(predicates);
        Objects.requireNonNull(disposition, "disposition");
        if (triggered != (disposition == Disposition.TRIGGERED)
                || disposition == Disposition.TRIGGERED
                && predicates.values().stream().anyMatch(result -> result != PredicateResult.PASS)) {
            throw invalid("R3_PROBE_DISPOSITION_INVALID");
        }
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw invalid("R3_PROBE_REASON_INVALID");
        }
        externalProviderUsage = Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
        if (!externalProviderUsage.zeroUsage()) throw invalid("R3_PROBE_PROVIDER_USAGE_NONZERO");
    }

    public static R3OrderRepeatProbeEvidence from(
            RapidOcrShadowReport report,
            boolean independentScorerReplayPassed
    ) {
        Objects.requireNonNull(report, "report");
        var corpus = new LayeredVisualCorpus();
        var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
        var expectedEvaluation = RapidOcrShadowEvaluationIdentity.exact(corpus, policy).identity();
        if (!expectedEvaluation.equals(report.evaluationIdentity())
                || !corpus.corpusIdentity().equals(report.corpusIdentity())
                || !corpus.annotationSetIdentity().equals(report.annotationSetIdentity())) {
            throw invalid("R3_PROBE_SOURCE_IDENTITY_DRIFT");
        }
        if (!report.determinism().deterministic()
                || report.determinism().metricsEquivalentCases() != 60
                || report.determinism().observationEquivalentCases() != 60) {
            throw invalid("R3_PROBE_SOURCE_NONDETERMINISTIC");
        }
        var components = report.evaluationComponents();
        if (!RapidOcrShadowCaseEvaluator.VERSION.equals(components.get("caseEvaluatorIdentity"))
                || !DocumentObservationCompatibilityProjection.VERSION.equals(components.get("projectionIdentity"))) {
            throw invalid("R3_PROBE_COMPARISON_SEAM_DRIFT");
        }

        var protocol = OfflineQualityEvaluationProtocol.load();
        var assignment = protocol.r3ProbeAssignment();
        var firstById = recordsById(report.runs().getFirst().records());
        var secondById = recordsById(report.runs().getLast().records());
        var selected = assignment.caseIds().stream().map(caseId -> {
            var first = firstById.get(caseId);
            var second = secondById.get(caseId);
            if (first == null || !first.metricsEquivalent(second)) {
                throw invalid("R3_PROBE_CASE_NONDETERMINISTIC");
            }
            return caseEvidence(first);
        }).toList();

        var symptomObserved = selected.stream().anyMatch(CaseEvidence::orderOrRepeatDefectObserved);
        var omissionExcluded = selected.stream().allMatch(item ->
                item.matchedLines() == item.expectedLines() && item.allReferencedRegionsObserved());
        var predicates = new EnumMap<Predicate, PredicateResult>(Predicate.class);
        predicates.put(Predicate.EXACT_ASSIGNMENT, PredicateResult.PASS);
        predicates.put(Predicate.TWO_RUN_DETERMINISM, PredicateResult.PASS);
        predicates.put(Predicate.COMPATIBILITY_PROJECTION_REPLAYED, PredicateResult.PASS);
        predicates.put(Predicate.GOLD_PRECEDENCE_COMPARED, PredicateResult.PASS);
        predicates.put(Predicate.GOLD_REPEAT_MEMBERSHIP_COMPARED, PredicateResult.PASS);
        predicates.put(Predicate.ORDER_OR_REPEAT_DEFECT_OBSERVED,
                symptomObserved ? PredicateResult.PASS : PredicateResult.FAIL);
        predicates.put(Predicate.OCR_OMISSION_EXCLUDED,
                omissionExcluded ? PredicateResult.PASS : PredicateResult.FAIL);
        predicates.put(Predicate.PROMPT_SHAPE_EXCLUDED, PredicateResult.MISSING);
        predicates.put(Predicate.MATERIALIZER_EXCLUDED, PredicateResult.MISSING);
        predicates.put(Predicate.SCORER_EXCLUDED,
                independentScorerReplayPassed ? PredicateResult.PASS : PredicateResult.MISSING);
        predicates.put(Predicate.EXCLUSIVE_ORDER_REPEAT_CAUSALITY, PredicateResult.MISSING);

        var disposition = symptomObserved ? Disposition.MISSING : Disposition.NOT_TRIGGERED;
        var reason = !symptomObserved
                ? "R3_ORDER_REPEAT_DEFECT_NOT_OBSERVED"
                : !omissionExcluded
                ? "R3_OCR_OMISSION_NOT_EXCLUDED"
                : !independentScorerReplayPassed
                ? "R3_SCORER_REPLAY_MISSING"
                : "R3_DOWNSTREAM_CAUSAL_SEPARATION_MISSING";
        var external = report.externalProvider();
        return new R3OrderRepeatProbeEvidence(
                VERSION,
                protocol.identity(),
                assignment.identity(),
                report.evaluationIdentity(),
                new RapidOcrShadowReportJsonCodec().reportIdentity(report),
                2,
                3,
                1,
                selected,
                predicates,
                disposition,
                false,
                reason,
                new ExternalProviderUsage(
                        external.attempts(), external.reservations(), external.costMicrosCny()));
    }

    private static Map<String, RapidOcrShadowCaseRecord> recordsById(
            List<RapidOcrShadowCaseRecord> source
    ) {
        var result = new java.util.HashMap<String, RapidOcrShadowCaseRecord>();
        for (var item : source) {
            if (result.putIfAbsent(item.caseId(), item) != null) {
                throw invalid("R3_PROBE_DUPLICATE_CASE");
            }
        }
        return Map.copyOf(result);
    }

    private static CaseEvidence caseEvidence(RapidOcrShadowCaseRecord value) {
        var orderErrors = value.order().errors();
        var missingMemberships = value.repeat().expectedMemberships()
                - value.repeat().observableMemberships();
        return new CaseEvidence(
                value.caseId(), value.caseIdentity(), value.partition(),
                value.layout().lines().expected(), value.layout().lines().matched(),
                value.order().expectedEdges(), value.order().comparableEdges(),
                value.order().correctEdges(), value.order().allReferencedRegionsObserved(),
                value.repeat().expectedMemberships(), value.repeat().observableMemberships(),
                orderErrors > 0 || missingMemberships > 0);
    }

    private static Map<Predicate, PredicateResult> canonicalPredicates(
            Map<Predicate, PredicateResult> source
    ) {
        var result = new EnumMap<Predicate, PredicateResult>(Predicate.class);
        result.putAll(Objects.requireNonNull(source, "predicates"));
        if (result.size() != Predicate.values().length
                || result.values().stream().anyMatch(Objects::isNull)) {
            throw invalid("R3_PROBE_PREDICATE_SET_INVALID");
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
        COMPATIBILITY_PROJECTION_REPLAYED,
        GOLD_PRECEDENCE_COMPARED,
        GOLD_REPEAT_MEMBERSHIP_COMPARED,
        ORDER_OR_REPEAT_DEFECT_OBSERVED,
        OCR_OMISSION_EXCLUDED,
        PROMPT_SHAPE_EXCLUDED,
        MATERIALIZER_EXCLUDED,
        SCORER_EXCLUDED,
        EXCLUSIVE_ORDER_REPEAT_CAUSALITY
    }

    public enum PredicateResult { PASS, FAIL, MISSING }

    public enum Disposition { TRIGGERED, NOT_TRIGGERED, MISSING }

    public record CaseEvidence(
            String caseId,
            String caseIdentity,
            LayeredEvaluationRecord.Partition partition,
            long expectedLines,
            long matchedLines,
            long expectedPrecedenceEdges,
            long comparablePrecedenceEdges,
            long correctPrecedenceEdges,
            boolean allReferencedRegionsObserved,
            long expectedRepeatMemberships,
            long observableRepeatMemberships,
            boolean orderOrRepeatDefectObserved
    ) {
        public CaseEvidence {
            if (caseId == null || !caseId.matches("[a-z][a-z0-9-]{0,127}")) {
                throw invalid("R3_PROBE_CASE_ID_INVALID");
            }
            requireIdentity(caseIdentity, "R3_PROBE_CASE_IDENTITY_INVALID");
            Objects.requireNonNull(partition, "partition");
            if (expectedLines < 0 || matchedLines < 0 || matchedLines > expectedLines
                    || expectedPrecedenceEdges < 0 || comparablePrecedenceEdges < 0
                    || correctPrecedenceEdges < 0 || correctPrecedenceEdges > comparablePrecedenceEdges
                    || comparablePrecedenceEdges > expectedPrecedenceEdges
                    || expectedRepeatMemberships < 0 || observableRepeatMemberships < 0
                    || observableRepeatMemberships > expectedRepeatMemberships) {
                throw invalid("R3_PROBE_CASE_METRIC_INVALID");
            }
        }
    }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) {
        public ExternalProviderUsage {
            if (attempts < 0 || reservations < 0 || costMicrosCny < 0) {
                throw invalid("R3_PROBE_PROVIDER_USAGE_INVALID");
            }
        }

        public boolean zeroUsage() {
            return attempts == 0 && reservations == 0 && costMicrosCny == 0;
        }
    }
}
