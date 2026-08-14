package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredEvaluationRecord;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineQualityEvaluationProtocolTest {
    @Test
    void freezesShadowAuthorityAssignmentsMetricsAndThresholdsBeforeResults() {
        var protocol = OfflineQualityEvaluationProtocol.load();
        var corpus = new LayeredVisualCorpus();

        assertEquals(corpus.corpusIdentity(), protocol.corpusIdentity());
        assertTrue(protocol.shadowDiagnostic());
        assertFalse(protocol.certificationEligible());
        assertEquals("renderweave-visual-stage-corpus/1.0", protocol.finalAuthorityCorpusVersion());
        assertEquals(45, protocol.r2DevAssignment().caseIds().size());
        assertEquals(15, protocol.r2HoldoutCount());
        assertTrue(protocol.r2DevAssignment().caseIds().stream().allMatch(caseId ->
                corpus.require(caseId).partition() == LayeredEvaluationRecord.Partition.DEV));
        assertEquals(4, protocol.r3ProbeAssignment().caseIds().size());
        assertEquals(4, protocol.r5ProbeAssignment().caseIds().size());
        assertProbePartitions(protocol.r3ProbeAssignment(), corpus);
        assertProbePartitions(protocol.r5ProbeAssignment(), corpus);
        assertFalse(protocol.r3ProbeAssignment().caseIds().equals(protocol.r5ProbeAssignment().caseIds()));

        assertEquals(500, protocol.thresholds().minimumStructuralImprovementBps());
        assertEquals(100, protocol.thresholds().maximumNonRegressionBps());
        assertEquals(1, protocol.thresholds().minimumDownstreamMetricsImproved());
        assertEquals(0, protocol.thresholds().maximumCriticalHallucinationIncrease());
        assertTrue(protocol.structuralMetrics().contains("LAYOUT_RECALL_BPS"));
        assertTrue(protocol.downstreamMetrics().contains("CANDIDATE_TOPOLOGY_SIMILARITY_BPS"));
        assertEquals(List.of("STRUCTURAL_MARGIN_DESC", "DOWNSTREAM_MARGIN_DESC",
                        "CRITICAL_HALLUCINATIONS_ASC", "FAILURE_RATE_ASC", "P95_LATENCY_ASC",
                        "PEAK_RAM_ASC", "CONFIGURATION_ID_ASC"), protocol.winnerTieBreak());
        assertTrue(protocol.identity().matches(
                "renderweave-offline-quality-evaluation-protocol/1\\.0:[0-9a-f]{64}"));
    }

    @Test
    void doesNotExposeHoldoutCasesBeforeAContentAddressedSelectionModuleExists() {
        var protocol = OfflineQualityEvaluationProtocol.load();

        assertEquals(15, protocol.r2HoldoutCount());
        assertFalse(java.util.Arrays.stream(OfflineQualityEvaluationProtocol.class.getMethods())
                .anyMatch(method -> method.getName().equals("authorizeR2Holdout")));
    }

    private static void assertProbePartitions(
            OfflineQualityEvaluationProtocol.Assignment assignment,
            LayeredVisualCorpus corpus
    ) {
        assertEquals(3, assignment.caseIds().stream().filter(caseId ->
                corpus.require(caseId).partition() == LayeredEvaluationRecord.Partition.DEV).count());
        assertEquals(1, assignment.caseIds().stream().filter(caseId ->
                corpus.require(caseId).partition() == LayeredEvaluationRecord.Partition.HOLDOUT).count());
    }
}
