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

    @Test
    void generatedTrackAndCellEntriesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.GENERATED_TRACK_AND_CELL_ENTRIES,
                99_999).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.GENERATED_TRACK_AND_CELL_ENTRIES,
                100_000).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.GENERATED_TRACK_AND_CELL_ENTRIES,
                        100_001)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.EVALUATION_BUDGET_EXCEEDED, problem.code());
        assertEquals("closureAndExpansion.generatedTrackAndCellEntries",
                problem.limitId().orElseThrow().value());

        var request = guard.newRequestTracker();
        assertTrue(request.reserve(
                RenderingPipelineCapacityGuard.Limit.GENERATED_TRACK_AND_CELL_ENTRIES,
                60_000).isEmpty());
        assertTrue(request.reserve(
                RenderingPipelineCapacityGuard.Limit.GENERATED_TRACK_AND_CELL_ENTRIES,
                40_000).isEmpty());
        var cumulativeProblem = request.reserve(
                        RenderingPipelineCapacityGuard.Limit.GENERATED_TRACK_AND_CELL_ENTRIES,
                        1)
                .orElseThrow();
        assertEquals(ProblemCode.EVALUATION_BUDGET_EXCEEDED, cumulativeProblem.code());
        assertEquals("closureAndExpansion.generatedTrackAndCellEntries",
                cumulativeProblem.limitId().orElseThrow().value());
    }

    @Test
    void logicalOperationsBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.LOGICAL_OPERATIONS,
                999_999).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.LOGICAL_OPERATIONS,
                1_000_000).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.LOGICAL_OPERATIONS,
                        1_000_001)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.EVALUATION_BUDGET_EXCEEDED, problem.code());
        assertEquals("closureAndExpansion.logicalOperations",
                problem.limitId().orElseThrow().value());

        var request = guard.newRequestTracker();
        assertTrue(request.reserve(
                RenderingPipelineCapacityGuard.Limit.LOGICAL_OPERATIONS,
                600_000).isEmpty());
        assertTrue(request.reserve(
                RenderingPipelineCapacityGuard.Limit.LOGICAL_OPERATIONS,
                400_000).isEmpty());
        var cumulativeProblem = request.reserve(
                        RenderingPipelineCapacityGuard.Limit.LOGICAL_OPERATIONS,
                        1)
                .orElseThrow();
        assertEquals(ProblemCode.EVALUATION_BUDGET_EXCEEDED, cumulativeProblem.code());
        assertEquals("closureAndExpansion.logicalOperations",
                cumulativeProblem.limitId().orElseThrow().value());
    }

    @Test
    void renderDocumentCanonicalBytesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CANONICAL_BYTES,
                67_108_863).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CANONICAL_BYTES,
                67_108_864).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CANONICAL_BYTES,
                        67_108_865)
                .orElseThrow();
        assertEquals(EvaluationStage.DOCUMENT_SEAL, problem.stage());
        assertEquals(ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED, problem.code());
        assertEquals("renderDocument.canonicalBytes",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void renderDocumentJsonDepthBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_JSON_DEPTH,
                127).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_JSON_DEPTH,
                128).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_JSON_DEPTH,
                        129)
                .orElseThrow();
        assertEquals(EvaluationStage.DOCUMENT_SEAL, problem.stage());
        assertEquals(ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED, problem.code());
        assertEquals("renderDocument.jsonDepth",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void renderDocumentStaticNodesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_STATIC_NODES,
                19_999).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_STATIC_NODES,
                20_000).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_STATIC_NODES,
                        20_001)
                .orElseThrow();
        assertEquals(EvaluationStage.DOCUMENT_SEAL, problem.stage());
        assertEquals(ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED, problem.code());
        assertEquals("renderDocument.staticNodes",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void renderDocumentChildEdgesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CHILD_EDGES,
                19_998).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CHILD_EDGES,
                19_999).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CHILD_EDGES,
                        20_000)
                .orElseThrow();
        assertEquals(EvaluationStage.DOCUMENT_SEAL, problem.stage());
        assertEquals(ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED, problem.code());
        assertEquals("renderDocument.childEdges",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void renderDocumentRunsBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_RUNS,
                9_999).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_RUNS,
                10_000).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_RUNS,
                        10_001)
                .orElseThrow();
        assertEquals(EvaluationStage.DOCUMENT_SEAL, problem.stage());
        assertEquals(ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED, problem.code());
        assertEquals("renderDocument.runs",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void renderDocumentTextScalarsBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_TEXT_SCALARS,
                999_999).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_TEXT_SCALARS,
                1_000_000).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_TEXT_SCALARS,
                        1_000_001)
                .orElseThrow();
        assertEquals(EvaluationStage.DOCUMENT_SEAL, problem.stage());
        assertEquals(ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED, problem.code());
        assertEquals("renderDocument.textScalars",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void renderDocumentVectorEntriesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_VECTOR_ENTRIES,
                99_999).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_VECTOR_ENTRIES,
                100_000).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_VECTOR_ENTRIES,
                        100_001)
                .orElseThrow();
        assertEquals(EvaluationStage.DOCUMENT_SEAL, problem.stage());
        assertEquals(ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED, problem.code());
        assertEquals("renderDocument.vectorEntries",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void diagnosticSidecarItemsBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.DIAGNOSTICS_SIDECAR_ITEMS,
                24_999).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit.DIAGNOSTICS_SIDECAR_ITEMS,
                25_000).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit.DIAGNOSTICS_SIDECAR_ITEMS,
                        25_001)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.RENDER_DIAGNOSTIC_LIMIT_EXCEEDED, problem.code());
        assertEquals("diagnostics.sidecarItems",
                problem.limitId().orElseThrow().value());
    }
}
