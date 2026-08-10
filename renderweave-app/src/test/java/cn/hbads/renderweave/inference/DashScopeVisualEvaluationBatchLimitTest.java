package cn.hbads.renderweave.inference;

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
}
