package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderingPipelineCapacityGuardTest {

    @Test
    void compositionViewportBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.COMPOSITION_VIEWPORTS, 255).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.COMPOSITION_VIEWPORTS, 256).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.COMPOSITION_VIEWPORTS, 257)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.EVALUATION_BUDGET_EXCEEDED, problem.code());
        assertEquals("closureAndExpansion.compositionViewports",
                problem.limitId().orElseThrow().value());
    }
}
