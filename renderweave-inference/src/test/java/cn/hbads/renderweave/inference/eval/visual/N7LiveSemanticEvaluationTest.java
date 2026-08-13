package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class N7LiveSemanticEvaluationTest {
    private static final List<String> CANARY = List.of(
            "transit-board-v3",
            "restaurant-menu-v2",
            "invoice-lines-v4",
            "building-directory-v1",
            "low-information-poster-v3"
    );

    @Test
    void reportBindsTheActualLiveSemanticScorerToTheExactLayeredAssignment() {
        var corpus = new LayeredVisualCorpus();
        var evaluation = new N7LiveSemanticEvaluation();
        var binding = new N7LiveSemanticEvaluation.Binding(
                "n7-04-plus-canary-product-v45-20260813b",
                "CANARY",
                "renderweave-visual-evaluation-tree-sha256/2:" + "a".repeat(64),
                "dashscope-qwen37-plus-product-v45-hybrid-generic",
                "b".repeat(64),
                "renderweave-n7-qualification-protocol/1.0:" + "c".repeat(64),
                "renderweave-n7-canary-assignment/1.0:" + "d".repeat(64),
                CANARY
        );
        var results = CANARY.stream().map(caseId -> evaluation.evaluateFailure(
                corpus.require(caseId), VisualStageSnapshot.empty(InferenceStage.NORMALIZE),
                "VISUAL_STAGE_CANDIDATE_MISSING"
        )).toList();

        var report = evaluation.report(corpus, binding, results);
        var codec = new N7LiveSemanticEvaluationReportJsonCodec();
        var encoded = codec.write(report);
        var identity = codec.reportIdentity(report);

        assertEquals(N7LiveSemanticEvaluationReport.VERSION, report.reportVersion());
        assertEquals(N7LiveSemanticEvaluation.evaluatorIdentity(), report.evaluatorIdentity());
        assertNotEquals(LayeredR1Evaluation.evaluatorIdentity(), report.evaluatorIdentity());
        assertEquals(LayeredVisualCorpus.VERSION, report.corpusVersion());
        assertEquals(corpus.corpusIdentity(), report.corpusIdentity());
        assertEquals(CANARY, report.expectedCaseIds());
        assertEquals(CANARY, report.observedCaseIds());
        assertTrue(report.complete());
        assertEquals(5, report.global().caseCount());
        assertEquals(report, codec.read(encoded, identity));
        assertFalse(new String(encoded, java.nio.charset.StandardCharsets.UTF_8)
                .contains("IGNORE PRIOR INSTRUCTIONS"));
    }

    @Test
    void reportRejectsDuplicateOrOutOfAssignmentResults() {
        var corpus = new LayeredVisualCorpus();
        var evaluation = new N7LiveSemanticEvaluation();
        var binding = new N7LiveSemanticEvaluation.Binding(
                "n7-test", "CANARY",
                "renderweave-visual-evaluation-tree-sha256/2:" + "a".repeat(64),
                "dashscope-qwen37-plus-product-v45-hybrid-generic", "b".repeat(64),
                "renderweave-n7-qualification-protocol/1.0:" + "c".repeat(64),
                "renderweave-n7-canary-assignment/1.0:" + "d".repeat(64), CANARY
        );
        var result = evaluation.evaluateFailure(
                corpus.require(CANARY.getFirst()),
                VisualStageSnapshot.empty(InferenceStage.NORMALIZE),
                "VISUAL_STAGE_CANDIDATE_MISSING"
        );
        var outside = evaluation.evaluateFailure(
                corpus.require("transit-board-v1"),
                VisualStageSnapshot.empty(InferenceStage.NORMALIZE),
                "VISUAL_STAGE_CANDIDATE_MISSING"
        );

        assertThrows(IllegalArgumentException.class,
                () -> evaluation.report(corpus, binding, List.of(result, result)));
        assertThrows(IllegalArgumentException.class,
                () -> evaluation.report(corpus, binding, List.of(outside)));
    }
}
