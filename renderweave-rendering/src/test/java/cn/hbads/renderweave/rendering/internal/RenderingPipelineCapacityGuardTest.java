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

    @Test
    void repeatCollectionBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.REPEAT_COLLECTION_ITEMS_PER_OCCURRENCE,
                0).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.REPEAT_COLLECTION_ITEMS_PER_OCCURRENCE,
                999).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.REPEAT_COLLECTION_ITEMS_PER_OCCURRENCE,
                1_000).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit
                                .REPEAT_COLLECTION_ITEMS_PER_OCCURRENCE,
                        1_001)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.EVALUATION_BUDGET_EXCEEDED, problem.code());
        assertEquals("closureAndExpansion.repeatCollectionItemsPerOccurrence",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void repeatNestingBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.REPEAT_NESTING_DEPTH,
                7).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.REPEAT_NESTING_DEPTH,
                8).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.REPEAT_NESTING_DEPTH,
                        9)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.EVALUATION_BUDGET_EXCEEDED, problem.code());
        assertEquals("closureAndExpansion.repeatNestingDepth",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void loopFramesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.LOOP_FRAMES_TOTAL,
                9_999).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.LOOP_FRAMES_TOTAL,
                10_000).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.LOOP_FRAMES_TOTAL,
                        10_001)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.EVALUATION_BUDGET_EXCEEDED, problem.code());
        assertEquals("closureAndExpansion.loopFramesTotal",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void renderOccurrencesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_OCCURRENCES,
                24_999).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_OCCURRENCES,
                25_000).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.RENDER_OCCURRENCES,
                        25_001)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.EVALUATION_BUDGET_EXCEEDED, problem.code());
        assertEquals("closureAndExpansion.renderOccurrences",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void materializedStaticNodesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.MATERIALIZED_STATIC_NODES,
                19_999).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.MATERIALIZED_STATIC_NODES,
                20_000).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.MATERIALIZED_STATIC_NODES,
                        20_001)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.EVALUATION_BUDGET_EXCEEDED, problem.code());
        assertEquals("closureAndExpansion.materializedStaticNodes",
                problem.limitId().orElseThrow().value());
    }
}
