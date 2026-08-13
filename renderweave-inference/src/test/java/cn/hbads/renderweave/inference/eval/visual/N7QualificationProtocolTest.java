package cn.hbads.renderweave.inference.eval.visual;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class N7QualificationProtocolTest {
    @Test
    void freezesTriggerDispositionsAssignmentsAuthorityAndProfileBindings() {
        var protocol = N7QualificationProtocol.load();
        var corpusV2 = new LayeredVisualCorpus();
        var corpusV1 = new VisualStageCorpus();

        assertEquals("CONTINUE_N7_CURRENT_BEHAVIOR", protocol.continuationCode());
        assertEquals(Set.of("R2", "R3", "R4", "R5"), protocol.triggerDispositions().keySet());
        assertTrue(protocol.triggerDispositions().values().stream()
                .allMatch(item -> item.status() == N7QualificationProtocol.TriggerStatus.NOT_TRIGGERED));
        assertEquals(5, protocol.canaryCaseIds().size());
        assertEquals(20, protocol.qualificationCaseIds().size());
        assertTrue(protocol.qualificationCaseIds().containsAll(protocol.canaryCaseIds()));
        assertEquals(20, Set.copyOf(protocol.qualificationCaseIds()).size());
        assertTrue(protocol.canaryCaseIds().stream()
                .allMatch(caseId -> corpusV2.require(caseId).partition()
                        == LayeredEvaluationRecord.Partition.DEV));
        assertTrue(protocol.qualificationCaseIds().stream()
                .allMatch(caseId -> corpusV2.require(caseId).partition()
                        == LayeredEvaluationRecord.Partition.DEV));
        assertEquals(corpusV1.cases().stream().map(VisualStageCorpus.EvaluationCase::caseId).toList(),
                protocol.finalCaseIds());
        assertEquals(45, protocol.finalCaseIds().stream()
                .map(corpusV1::require).filter(item -> item.partition() == VisualStageCorpus.Partition.DEV).count());
        assertEquals(15, protocol.finalHoldoutCaseIds().size());
        assertEquals("renderweave-visual-stage-corpus/1.0", protocol.finalCorpusVersion());
        assertEquals(corpusV1.sourceSha256(), protocol.finalCorpusSourceSha256());
        assertEquals("qwen3.7-plus", protocol.plus().model());
        assertEquals("qwen3.8-max", protocol.max().model());
        assertFalse(protocol.flash().available());
        assertEquals("qwen3.7-flash-2026-07-15", protocol.flash().model());
        assertEquals("PINNED_PRODUCT_V45_PROFILE_ABSENT", protocol.flash().reasonCode());
        assertTrue(protocol.identity().matches(
                "renderweave-n7-qualification-protocol/1\\.0:[0-9a-f]{64}"));
        assertTrue(protocol.canaryAssignmentIdentity().matches(
                "renderweave-n7-canary-assignment/1\\.0:[0-9a-f]{64}"));
        assertTrue(protocol.qualificationAssignmentIdentity().matches(
                "renderweave-n7-qualification-assignment/1\\.0:[0-9a-f]{64}"));
        assertTrue(protocol.finalAssignmentIdentity().matches(
                "renderweave-n7-final-assignment/1\\.0:[0-9a-f]{64}"));
    }

    @Test
    void challengerMatrixIsFailClosedAtEveryFrozenBoundary() {
        var protocol = N7QualificationProtocol.load();
        var passing = N7QualificationProtocol.QualityMetrics.atThresholds();
        var belowHard = new N7QualificationProtocol.QualityMetrics(8_999, 9_000, 9_500, 9_500);

        assertEquals(N7QualificationProtocol.ChallengerRoute.STOP_TO_SPEC,
                protocol.route(N7QualificationProtocol.QualificationEvidence.complete(passing, passing)));
        assertEquals(N7QualificationProtocol.ChallengerRoute.MAX_ELIGIBLE,
                protocol.route(N7QualificationProtocol.QualificationEvidence.complete(passing, belowHard)));
        assertEquals(N7QualificationProtocol.ChallengerRoute.NO_CHALLENGER,
                protocol.route(N7QualificationProtocol.QualificationEvidence.complete(belowHard, belowHard)));
        assertEquals(N7QualificationProtocol.ChallengerRoute.NO_CHALLENGER,
                protocol.route(N7QualificationProtocol.QualificationEvidence.integrityFailure(passing, passing)));
        assertEquals(N7QualificationProtocol.ChallengerRoute.STOP_TO_SPEC,
                protocol.route(N7QualificationProtocol.QualificationEvidence.requiringAlgorithmChange()));
        assertThrows(IllegalArgumentException.class,
                () -> protocol.route(N7QualificationProtocol.QualificationEvidence.missingMetrics()));
    }

    @Test
    void selectionUsesQualityBandThenCostLatencyAndStableProfileId() {
        var protocol = N7QualificationProtocol.load();
        var plus = candidate(protocol.plus().profileId(), 9_300, 500, 20_000);
        var max = candidate(protocol.max().profileId(), 9_250, 1_000, 10_000);
        assertEquals(protocol.plus().profileId(), protocol.selectFinalist(List.of(max, plus)).profileId());

        var materiallyBetterMax = candidate(protocol.max().profileId(), 9_800, 2_000, 30_000);
        assertEquals(protocol.max().profileId(),
                protocol.selectFinalist(List.of(plus, materiallyBetterMax)).profileId());

        var sameCostSlow = candidate(protocol.max().profileId(), 9_300, 500, 30_000);
        assertEquals(protocol.plus().profileId(),
                protocol.selectFinalist(List.of(sameCostSlow, plus)).profileId());

        var lexicalMax = candidate(protocol.max().profileId(), 9_300, 500, 20_000);
        assertEquals(protocol.plus().profileId(),
                protocol.selectFinalist(List.of(plus, lexicalMax)).profileId());
        assertEquals("NO_FINALIST", protocol.selectFinalist(List.of(
                new N7QualificationProtocol.QualificationCandidate(
                        protocol.plus().profileId(), false,
                        N7QualificationProtocol.QualityMetrics.atThresholds(), 1, 1,
                        "report/1.0:" + "a".repeat(64), "snapshot:" + "b".repeat(64)))).reasonCode());
    }

    private static N7QualificationProtocol.QualificationCandidate candidate(
            String profileId,
            int weakestMetric,
            long cost,
            long latency
    ) {
        return new N7QualificationProtocol.QualificationCandidate(
                profileId, true,
                new N7QualificationProtocol.QualityMetrics(
                        weakestMetric, weakestMetric, Math.max(9_500, weakestMetric),
                        Math.max(9_500, weakestMetric)),
                cost, latency, "report/1.0:" + "a".repeat(64), "snapshot:" + "b".repeat(64));
    }
}
