package cn.hbads.renderweave.inference.eval.visual;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Payload-free semantic scorecard for one exact N7 live assignment over corpus v2 renders. */
public record N7LiveSemanticEvaluationReport(
        String reportVersion,
        String evaluatorIdentity,
        String authorizationId,
        String phase,
        String repositoryEvaluationIdentity,
        String profileId,
        String profileSnapshotSha256,
        String qualificationProtocolIdentity,
        String assignmentIdentity,
        String corpusVersion,
        String corpusIdentity,
        String corpusSourceSha256,
        List<String> expectedCaseIds,
        List<String> observedCaseIds,
        boolean complete,
        VisualStageReport.Aggregate global,
        Map<String, VisualStageReport.Aggregate> partitions,
        Map<String, VisualStageReport.Aggregate> styles,
        Map<String, VisualStageReport.Aggregate> domainPacks
) {
    public static final String VERSION = "renderweave-n7-live-semantic-report/1.0";

    public N7LiveSemanticEvaluationReport {
        if (!VERSION.equals(reportVersion)
                || !N7LiveSemanticEvaluation.evaluatorIdentity().equals(evaluatorIdentity)
                || authorizationId == null || !authorizationId.matches("[a-z][a-z0-9-]{0,127}")
                || phase == null || !phase.matches("[A-Z][A-Z0-9_]{0,31}")
                || repositoryEvaluationIdentity == null
                || !repositoryEvaluationIdentity.matches(
                "renderweave-visual-evaluation-tree-sha256/[12]:[0-9a-f]{64}")
                || profileId == null || !profileId.matches("[a-z][a-z0-9-]{0,127}")
                || profileSnapshotSha256 == null || !profileSnapshotSha256.matches("[0-9a-f]{64}")
                || !LayeredVisualCorpus.VERSION.equals(corpusVersion)
                || corpusSourceSha256 == null || !corpusSourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("N7_LIVE_REPORT_ENVELOPE_INVALID");
        }
        LayeredVisualAnnotation.requireIdentity(qualificationProtocolIdentity,
                "N7_LIVE_REPORT_PROTOCOL_IDENTITY_INVALID");
        LayeredVisualAnnotation.requireIdentity(assignmentIdentity,
                "N7_LIVE_REPORT_ASSIGNMENT_IDENTITY_INVALID");
        LayeredVisualAnnotation.requireIdentity(corpusIdentity,
                "N7_LIVE_REPORT_CORPUS_IDENTITY_INVALID");
        expectedCaseIds = caseIds(expectedCaseIds, false, "N7_LIVE_REPORT_EXPECTED_CASES_INVALID");
        observedCaseIds = caseIds(observedCaseIds, true, "N7_LIVE_REPORT_OBSERVED_CASES_INVALID");
        if (!new HashSet<>(expectedCaseIds).containsAll(observedCaseIds)
                || complete != expectedCaseIds.equals(observedCaseIds)) {
            throw new IllegalArgumentException("N7_LIVE_REPORT_CASE_ACCOUNTING_INVALID");
        }
        Objects.requireNonNull(global, "global");
        partitions = Map.copyOf(Objects.requireNonNull(partitions, "partitions"));
        styles = Map.copyOf(Objects.requireNonNull(styles, "styles"));
        domainPacks = Map.copyOf(Objects.requireNonNull(domainPacks, "domainPacks"));
        if (global.caseCount() != observedCaseIds.size()) {
            throw new IllegalArgumentException("N7_LIVE_REPORT_AGGREGATE_COUNT_INVALID");
        }
    }

    private static List<String> caseIds(List<String> source, boolean emptyAllowed, String code) {
        source = List.copyOf(Objects.requireNonNull(source, "caseIds"));
        if ((!emptyAllowed && source.isEmpty()) || source.size() > 60
                || source.stream().anyMatch(item -> item == null
                || !item.matches("[a-z][a-z0-9-]{0,127}"))
                || new HashSet<>(source).size() != source.size()) {
            throw new IllegalArgumentException(code);
        }
        return source;
    }

    @Override
    public String toString() {
        return "N7LiveSemanticEvaluationReport[reportVersion=" + reportVersion
                + ", evaluatorIdentity=" + evaluatorIdentity + ", authorizationId=" + authorizationId
                + ", phase=" + phase + ", profileId=" + profileId + ", corpusIdentity=" + corpusIdentity
                + ", expectedCases=" + expectedCaseIds.size() + ", observedCases=" + observedCaseIds.size()
                + ", complete=" + complete + ", payload=<redacted>]";
    }
}
