package cn.hbads.renderweave.inference.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** AC-021 release policy. It decides evidence; it never mutates a Profile registry. */
public final class LiveCertificationPolicy {
    /** Builds the report from the version-bound corpus/results instead of trusting a caller-made report. */
    public LiveCertificationDecision decide(
            String profileId,
            LiveEvaluationCorpus corpus,
            List<LiveEvaluationResult> results
    ) {
        return decide(new LiveEvaluationReporter().report(profileId, corpus, results));
    }

    private LiveCertificationDecision decide(LiveEvaluationReport report) {
        if (!report.complete()) {
            return new LiveCertificationDecision(
                    LiveCertificationStatus.INCOMPLETE,
                    List.of("EVALUATION_INCOMPLETE")
            );
        }
        var violations = new ArrayList<String>();
        validateEnvelope(report, violations);
        checkFullSlice("GLOBAL", report.global(), violations);
        for (var mode : List.of("IMAGE_ONLY", "JSON_ONLY", "COMBINED")) {
            var slice = report.byMode().get(mode);
            if (slice == null || slice.caseCount() != 20) {
                violations.add("MODE_" + mode + "_CASE_COUNT_INVALID");
            } else {
                checkFullSlice("MODE_" + mode, slice, violations);
            }
        }
        var holdout = report.byPartition().get("HOLDOUT");
        if (holdout == null || holdout.caseCount() != 15) {
            violations.add("HOLDOUT_CASE_COUNT_INVALID");
        } else {
            exact("HOLDOUT_BUNDLE_CONTRACT", holdout.bundleContractBps(), violations);
            exact("HOLDOUT_EVIDENCE_COVERAGE", holdout.evidenceCoverageBps(), violations);
            exact("HOLDOUT_DAG_VALIDITY", holdout.dagValidityBps(), violations);
            if (holdout.criticalHallucinationCount() != 0) {
                violations.add("HOLDOUT_CRITICAL_HALLUCINATION_NONZERO");
            }
        }
        return new LiveCertificationDecision(
                violations.isEmpty() ? LiveCertificationStatus.CERTIFIED : LiveCertificationStatus.EXPERIMENTAL,
                violations
        );
    }

    private static void validateEnvelope(
            LiveEvaluationReport report,
            List<String> violations
    ) {
        if (!LiveEvaluationCorpus.VERSION.equals(report.corpusVersion())) {
            violations.add("CORPUS_VERSION_INVALID");
        }
        if (report.evaluatedCaseCount() != 60 || report.corpusCaseCount() != 60
                || report.global().caseCount() != 60 || !report.missingCaseIds().isEmpty()) {
            violations.add("GLOBAL_CASE_COUNT_INVALID");
        }
        if (!report.byMode().keySet().equals(Set.of("IMAGE_ONLY", "JSON_ONLY", "COMBINED"))) {
            violations.add("MODE_SLICES_INVALID");
        }
        if (!report.byPartition().keySet().equals(Set.of("DEV", "HOLDOUT"))
                || report.byPartition().get("DEV") == null
                || report.byPartition().get("DEV").caseCount() != 45) {
            violations.add("PARTITION_SLICES_INVALID");
        }
        var modeTotal = report.byMode().values().stream().mapToInt(LiveEvaluationSlice::caseCount).sum();
        var partitionTotal = report.byPartition().values().stream()
                .mapToInt(LiveEvaluationSlice::caseCount).sum();
        if (modeTotal != report.global().caseCount()
                || partitionTotal != report.global().caseCount()) {
            violations.add("SLICE_TOTALS_INVALID");
        }
    }

    private static void checkFullSlice(
            String prefix,
            LiveEvaluationSlice slice,
            List<String> violations
    ) {
        exact(prefix + "_BUNDLE_CONTRACT", slice.bundleContractBps(), violations);
        minimum(prefix + "_SCHEMA_ENTITY_F1", slice.schemaEntityF1Bps(), 9_000, violations);
        minimum(prefix + "_FIELD_MICRO_F1", slice.fieldF1Bps(), 9_000, violations);
        minimum(prefix + "_SUPPORTED_TYPE_ACCURACY", slice.supportedTypeAccuracyBps(), 9_500, violations);
        minimum(prefix + "_PARENT_CHILD_EDGE_F1", slice.parentChildEdgeF1Bps(), 9_500, violations);
        exact(prefix + "_EVIDENCE_COVERAGE", slice.evidenceCoverageBps(), violations);
        exact(prefix + "_DAG_VALIDITY", slice.dagValidityBps(), violations);
        if (slice.criticalHallucinationCount() != 0) {
            violations.add(prefix + "_CRITICAL_HALLUCINATION_NONZERO");
        }
    }

    private static void exact(String code, int value, List<String> violations) {
        if (value != 10_000) violations.add(code + "_BELOW_THRESHOLD");
    }

    private static void minimum(String code, int value, int minimum, List<String> violations) {
        if (value < minimum) violations.add(code + "_BELOW_THRESHOLD");
    }
}
