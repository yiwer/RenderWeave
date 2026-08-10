package cn.hbads.renderweave.inference.eval.visual;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualStageReporterTest {
    private final VisualStageCorpus corpus = new VisualStageCorpus();
    private final VisualStageEvaluator evaluator = new VisualStageEvaluator();
    private final VisualStageReporter reporter = new VisualStageReporter();

    @Test
    void aggregatesCompleteCorpusByMicroCountsAndAllRequiredSlices() {
        var results = corpus.cases().stream().map(item ->
                evaluator.evaluate(item, VisualStageEvaluatorTest.perfectSnapshot(item))).toList();
        var report = reporter.report(corpus, results);

        assertTrue(report.complete());
        assertEquals(60, report.observedCaseCount());
        assertTrue(report.missingCaseIds().isEmpty());
        assertEquals(10_000, report.global().elementF1Bps());
        assertEquals(10_000, report.global().grounding().recallAtIou50Bps());
        assertEquals(10_000, report.global().entities().f1Bps());
        assertEquals(10_000, report.global().relationships().f1Bps());
        assertEquals(10_000, report.global().bindings().f1Bps());
        assertEquals(10_000, report.global().survival().candidateSurvivalBps());
        assertEquals(10_000, report.global().normalizedTreeSimilarityBps());
        assertEquals(45, report.partitions().get("DEV").caseCount());
        assertEquals(15, report.partitions().get("HOLDOUT").caseCount());
        for (var style : VisualStageCorpus.Style.values()) {
            assertEquals(12, report.styles().get(style.name()).caseCount());
        }
        assertEquals(5, report.domainPacks().get("TRANSIT_BOARD").caseCount());
        assertEquals(55, report.domainPacks().get("GENERIC").caseCount());
    }

    @Test
    void partialReportNamesOnlyMissingSyntheticCaseIdsAndCannotClaimComplete() {
        var first = corpus.cases().getFirst();
        var report = reporter.report(corpus, List.of(
                evaluator.evaluate(first, VisualStageEvaluatorTest.perfectSnapshot(first))
        ));

        assertFalse(report.complete());
        assertEquals(1, report.observedCaseCount());
        assertEquals(59, report.missingCaseIds().size());
        assertFalse(report.missingCaseIds().contains(first.caseId()));
        assertEquals(1, report.global().caseCount());
    }

    @Test
    void duplicateCaseCannotInflateReport() {
        var first = corpus.cases().getFirst();
        var result = evaluator.evaluate(first, VisualStageEvaluatorTest.perfectSnapshot(first));
        assertThrows(IllegalArgumentException.class,
                () -> reporter.report(corpus, List.of(result, result)));
    }
}
