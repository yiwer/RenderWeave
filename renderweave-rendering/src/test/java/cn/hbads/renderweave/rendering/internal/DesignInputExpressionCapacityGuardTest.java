package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignInputExpressionCapacityGuardTest {

    @Test
    void sourceTotalBoundaryUsesFrozenProductionGuardContract() {
        var guard = new DesignInputExpressionCapacityGuard();
        var limit = DesignInputExpressionCapacityGuard.Limit.SOURCE_UTF8_BYTES_TOTAL;

        assertTrue(guard.admit(limit, 1_048_575).isEmpty());
        assertTrue(guard.admit(limit, 1_048_576).isEmpty());

        var problem = guard.admit(limit, 1_048_577).orElseThrow();
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, problem.stage());
        assertEquals(ProblemCode.EXPRESSION_LIMIT_EXCEEDED, problem.code());
        assertEquals("expression.sourceUtf8BytesTotal",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void sourceBudgetAccumulatesOneDslAndPreservesPerExpressionPrecedence() {
        var guard = new DesignInputExpressionCapacityGuard();
        var sourceBudget = guard.newSourceBudget();

        for (var index = 0; index < 16; index++) {
            assertTrue(sourceBudget.admit(65_536).isEmpty());
        }
        var totalProblem = sourceBudget.admit(1).orElseThrow();
        assertEquals("expression.sourceUtf8BytesTotal",
                totalProblem.limitId().orElseThrow().value());

        var perExpressionProblem = guard.newSourceBudget().admit(65_537).orElseThrow();
        assertEquals("expression.sourceUtf8BytesPerExpression",
                perExpressionProblem.limitId().orElseThrow().value());
    }

    @Test
    void sourceUtf8BoundaryUsesFrozenProductionGuardContract() {
        var guard = new DesignInputExpressionCapacityGuard();
        var limit = DesignInputExpressionCapacityGuard.Limit.SOURCE_UTF8_BYTES_PER_EXPRESSION;

        assertTrue(guard.admit(limit, 65_535).isEmpty());
        assertTrue(guard.admit(limit, 65_536).isEmpty());

        var problem = guard.admit(limit, 65_537).orElseThrow();
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, problem.stage());
        assertEquals(ProblemCode.EXPRESSION_LIMIT_EXCEEDED, problem.code());
        assertEquals("expression.sourceUtf8BytesPerExpression",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void explicitRoundingScaleBoundaryUsesFrozenProductionGuardContract() {
        var guard = new DesignInputExpressionCapacityGuard();
        var limit = DesignInputExpressionCapacityGuard.Limit.EXPLICIT_ROUNDING_SCALE_MAX;

        assertTrue(guard.admit(limit, 63).isEmpty());
        assertTrue(guard.admit(limit, 64).isEmpty());

        var problem = guard.admit(limit, 65).orElseThrow();
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, problem.stage());
        assertEquals(ProblemCode.EXPRESSION_LIMIT_EXCEEDED, problem.code());
        assertEquals("expression.explicitRoundingScaleMax",
                problem.limitId().orElseThrow().value());
    }
}
