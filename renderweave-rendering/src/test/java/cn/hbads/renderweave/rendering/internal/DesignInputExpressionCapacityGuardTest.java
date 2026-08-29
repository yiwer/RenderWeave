package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignInputExpressionCapacityGuardTest {

    @Test
    void mappingCasesTotalBoundaryUsesFrozenProductionGuardContract() {
        var guard = new DesignInputExpressionCapacityGuard();
        var limit = DesignInputExpressionCapacityGuard.Limit.MAPPING_CASES_TOTAL;

        assertTrue(guard.admit(limit, 8_191).isEmpty());
        assertTrue(guard.admit(limit, 8_192).isEmpty());

        var problem = guard.admit(limit, 8_193).orElseThrow();
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, problem.stage());
        assertEquals(ProblemCode.EXPRESSION_LIMIT_EXCEEDED, problem.code());
        assertEquals("expression.mappingCasesTotal",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void mappingCaseBudgetAccumulatesOneDslAndPreservesPerDefinitionPrecedence() {
        var guard = new DesignInputExpressionCapacityGuard();
        var caseBudget = guard.newMappingCaseBudget();

        var perDefinitionProblem = caseBudget.admit(257).orElseThrow();
        assertEquals("expression.mappingCasesPerDefinition",
                perDefinitionProblem.limitId().orElseThrow().value());

        for (var index = 0; index < 31; index++) {
            assertTrue(caseBudget.admit(256).isEmpty());
        }
        assertTrue(caseBudget.admit(255).isEmpty());
        assertTrue(caseBudget.admit(1).isEmpty());

        var totalProblem = caseBudget.admit(1).orElseThrow();
        assertEquals("expression.mappingCasesTotal",
                totalProblem.limitId().orElseThrow().value());
    }

    @Test
    void mappingCasesPerDefinitionBoundaryUsesFrozenProductionGuardContract() {
        var guard = new DesignInputExpressionCapacityGuard();
        var limit = DesignInputExpressionCapacityGuard.Limit.MAPPING_CASES_PER_DEFINITION;

        assertTrue(guard.admit(limit, 255).isEmpty());
        assertTrue(guard.admit(limit, 256).isEmpty());

        var problem = guard.admit(limit, 257).orElseThrow();
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, problem.stage());
        assertEquals(ProblemCode.EXPRESSION_LIMIT_EXCEEDED, problem.code());
        assertEquals("expression.mappingCasesPerDefinition",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void inputsTotalBoundaryUsesFrozenProductionGuardContract() {
        var guard = new DesignInputExpressionCapacityGuard();
        var limit = DesignInputExpressionCapacityGuard.Limit.INPUTS_TOTAL;

        assertTrue(guard.admit(limit, 4_095).isEmpty());
        assertTrue(guard.admit(limit, 4_096).isEmpty());

        var problem = guard.admit(limit, 4_097).orElseThrow();
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, problem.stage());
        assertEquals(ProblemCode.EXPRESSION_LIMIT_EXCEEDED, problem.code());
        assertEquals("expression.inputsTotal",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void inputBudgetAccumulatesOneDslAndPreservesPerExpressionPrecedence() {
        var guard = new DesignInputExpressionCapacityGuard();
        var inputBudget = guard.newInputBudget();

        for (var index = 0; index < 127; index++) {
            assertTrue(inputBudget.admit(32).isEmpty());
        }
        assertTrue(inputBudget.admit(31).isEmpty());
        assertTrue(inputBudget.admit(1).isEmpty());

        var totalProblem = inputBudget.admit(1).orElseThrow();
        assertEquals("expression.inputsTotal",
                totalProblem.limitId().orElseThrow().value());

        var perExpressionProblem = guard.newInputBudget().admit(33).orElseThrow();
        assertEquals("expression.inputsPerExpression",
                perExpressionProblem.limitId().orElseThrow().value());
    }

    @Test
    void inputsPerExpressionBoundaryUsesFrozenProductionGuardContract() {
        var guard = new DesignInputExpressionCapacityGuard();
        var limit = DesignInputExpressionCapacityGuard.Limit.INPUTS_PER_EXPRESSION;

        assertTrue(guard.admit(limit, 31).isEmpty());
        assertTrue(guard.admit(limit, 32).isEmpty());

        var problem = guard.admit(limit, 33).orElseThrow();
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, problem.stage());
        assertEquals(ProblemCode.EXPRESSION_LIMIT_EXCEEDED, problem.code());
        assertEquals("expression.inputsPerExpression",
                problem.limitId().orElseThrow().value());
    }

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
