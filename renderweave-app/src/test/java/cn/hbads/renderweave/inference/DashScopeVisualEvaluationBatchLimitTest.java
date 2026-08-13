package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.N7LiveSemanticEvaluation;
import cn.hbads.renderweave.inference.eval.visual.N7QualificationProtocol;
import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import cn.hbads.renderweave.inference.eval.visual.VisualStageRasterizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DashScopeVisualEvaluationBatchLimitTest {
    @Test
    void defaultsToAuthorizationAndAllowsOnlyNarrowerOperatorLimit() {
        assertThat(DashScopeVisualEvaluationTest.effectiveBatchLimit(5, null)).isEqualTo(5);
        assertThat(DashScopeVisualEvaluationTest.effectiveBatchLimit(5, " ")).isEqualTo(5);
        assertThat(DashScopeVisualEvaluationTest.effectiveBatchLimit(5, "1")).isEqualTo(1);
    }

    @Test
    void rejectsInvalidOrBroaderOperatorLimit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DashScopeVisualEvaluationTest.effectiveBatchLimit(5, "0"))
                .withMessage("VISUAL_EVALUATION_BATCH_LIMIT_INVALID");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DashScopeVisualEvaluationTest.effectiveBatchLimit(5, "6"))
                .withMessage("VISUAL_EVALUATION_BATCH_LIMIT_INVALID");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DashScopeVisualEvaluationTest.effectiveBatchLimit(5, "one"))
                .withMessage("VISUAL_EVALUATION_BATCH_LIMIT_INVALID");
    }

    @Test
    void haltsBatchAfterAnyProviderHttpOrGlobalFailure() {
        assertThat(DashScopeVisualEvaluationTest.shouldHalt("DASHSCOPE_HTTP_403")).isTrue();
        assertThat(DashScopeVisualEvaluationTest.shouldHalt("DASHSCOPE_HTTP_429")).isTrue();
        assertThat(DashScopeVisualEvaluationTest.shouldHalt("VISUAL_EVALUATION_GOAL_BUDGET_EXCEEDED"))
                .isTrue();
        assertThat(DashScopeVisualEvaluationTest.shouldHalt("LIVE_VISUAL_ANALYSIS_REJECTED"))
                .isFalse();
        assertThat(DashScopeVisualEvaluationTest.shouldHalt(null)).isFalse();
    }

    @Test
    void n7CanaryExecutesTheExactLayeredCorpusRenderAssignments() {
        var source = new VisualStageCorpus();
        var layered = new LayeredVisualCorpus();
        var rasterizer = new VisualStageRasterizer();

        for (var caseId : N7QualificationProtocol.load().canaryCaseIds()) {
            var selected = DashScopeVisualEvaluationTest.authorizedCase(
                    LayeredVisualCorpus.VERSION, caseId, source, layered);
            assertThat("render-sha256:" + rasterizer.render(selected).sha256())
                    .as(caseId)
                    .isEqualTo(layered.require(caseId).renderIdentity());
        }
        var injectionCase = "low-information-poster-v3";
        assertThat(rasterizer.render(DashScopeVisualEvaluationTest.authorizedCase(
                LayeredVisualCorpus.VERSION, injectionCase, source, layered)).sha256())
                .isNotEqualTo(rasterizer.render(source.require(injectionCase)).sha256());
    }

    @Test
    void n7ContractBindsTheEvaluatorActuallyUsedByTheLiveHarness() {
        assertThat(N7LiveTicketContract.plusCanary().evaluatorIdentity())
                .isEqualTo(N7LiveSemanticEvaluation.evaluatorIdentity());
    }
}
