package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void authoredAssetOccurrencesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_AUTHORED_ASSET_OCCURRENCES,
                4_095).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_AUTHORED_ASSET_OCCURRENCES,
                4_096).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit
                                .ASSETS_AND_FETCH_AUTHORED_ASSET_OCCURRENCES,
                        4_097)
                .orElseThrow();
        assertEquals(EvaluationStage.ASSET_ADMISSION, problem.stage());
        assertEquals(ProblemCode.ASSET_BUDGET_EXCEEDED, problem.code());
        assertEquals("assetsAndFetch.authoredAssetOccurrences",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void uniqueLogicalAssetsBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_UNIQUE_LOGICAL_ASSETS,
                511).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_UNIQUE_LOGICAL_ASSETS,
                512).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit
                                .ASSETS_AND_FETCH_UNIQUE_LOGICAL_ASSETS,
                        513)
                .orElseThrow();
        assertEquals(EvaluationStage.ASSET_ADMISSION, problem.stage());
        assertEquals(ProblemCode.ASSET_BUDGET_EXCEEDED, problem.code());
        assertEquals("assetsAndFetch.uniqueLogicalAssets",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void actualResolveOccurrencesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_ACTUAL_RESOLVE_OCCURRENCES,
                2_047).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_ACTUAL_RESOLVE_OCCURRENCES,
                2_048).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit
                                .ASSETS_AND_FETCH_ACTUAL_RESOLVE_OCCURRENCES,
                        2_049)
                .orElseThrow();
        assertEquals(EvaluationStage.ASSET_RESOLUTION, problem.stage());
        assertEquals(ProblemCode.RESOURCE_BUDGET_EXCEEDED, problem.code());
        assertEquals("assetsAndFetch.actualResolveOccurrences",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void renderResourceEntriesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_RENDER_RESOURCE_ENTRIES,
                2_047).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_RENDER_RESOURCE_ENTRIES,
                2_048).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit
                                .ASSETS_AND_FETCH_RENDER_RESOURCE_ENTRIES,
                        2_049)
                .orElseThrow();
        assertEquals(EvaluationStage.ASSET_RESOLUTION, problem.stage());
        assertEquals(ProblemCode.RESOURCE_BUDGET_EXCEEDED, problem.code());
        assertEquals("assetsAndFetch.renderResourceEntries",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void uniqueExactContentsBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_UNIQUE_EXACT_CONTENTS,
                127).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_UNIQUE_EXACT_CONTENTS,
                128).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit
                                .ASSETS_AND_FETCH_UNIQUE_EXACT_CONTENTS,
                        129)
                .orElseThrow();
        assertEquals(EvaluationStage.ASSET_ADMISSION, problem.stage());
        assertEquals(ProblemCode.ASSET_BUDGET_EXCEEDED, problem.code());
        assertEquals("assetsAndFetch.uniqueExactContents",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void occurrenceDeclaredRawBytesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_OCCURRENCE_DECLARED_RAW_BYTES,
                2_147_483_647L).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_OCCURRENCE_DECLARED_RAW_BYTES,
                2_147_483_648L).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit
                                .ASSETS_AND_FETCH_OCCURRENCE_DECLARED_RAW_BYTES,
                        2_147_483_649L)
                .orElseThrow();
        assertEquals(EvaluationStage.ASSET_ADMISSION, problem.stage());
        assertEquals(ProblemCode.ASSET_BUDGET_EXCEEDED, problem.code());
        assertEquals("assetsAndFetch.occurrenceDeclaredRawBytes",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void uniqueRawBytesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_UNIQUE_RAW_BYTES,
                268_435_455L).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_UNIQUE_RAW_BYTES,
                268_435_456L).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit
                                .ASSETS_AND_FETCH_UNIQUE_RAW_BYTES,
                        268_435_457L)
                .orElseThrow();
        assertEquals(EvaluationStage.ASSET_ADMISSION, problem.stage());
        assertEquals(ProblemCode.ASSET_BUDGET_EXCEEDED, problem.code());
        assertEquals("assetsAndFetch.uniqueRawBytes",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void occurrenceImagePixelsBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_OCCURRENCE_IMAGE_PIXELS,
                999_999_999L).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_OCCURRENCE_IMAGE_PIXELS,
                1_000_000_000L).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit
                                .ASSETS_AND_FETCH_OCCURRENCE_IMAGE_PIXELS,
                        1_000_000_001L)
                .orElseThrow();
        assertEquals(EvaluationStage.ASSET_ADMISSION, problem.stage());
        assertEquals(ProblemCode.ASSET_BUDGET_EXCEEDED, problem.code());
        assertEquals("assetsAndFetch.occurrenceImagePixels",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void uniqueImagePixelsBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_UNIQUE_IMAGE_PIXELS,
                124_999_999L).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_UNIQUE_IMAGE_PIXELS,
                125_000_000L).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit
                                .ASSETS_AND_FETCH_UNIQUE_IMAGE_PIXELS,
                        125_000_001L)
                .orElseThrow();
        assertEquals(EvaluationStage.ASSET_ADMISSION, problem.stage());
        assertEquals(ProblemCode.ASSET_BUDGET_EXCEEDED, problem.code());
        assertEquals("assetsAndFetch.uniqueImagePixels",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void occurrenceFontBytesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_OCCURRENCE_FONT_BYTES,
                536_870_911L).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_OCCURRENCE_FONT_BYTES,
                536_870_912L).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit
                                .ASSETS_AND_FETCH_OCCURRENCE_FONT_BYTES,
                        536_870_913L)
                .orElseThrow();
        assertEquals(EvaluationStage.ASSET_ADMISSION, problem.stage());
        assertEquals(ProblemCode.ASSET_BUDGET_EXCEEDED, problem.code());
        assertEquals("assetsAndFetch.occurrenceFontBytes",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void uniqueFontBytesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_UNIQUE_FONT_BYTES,
                67_108_863L).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_UNIQUE_FONT_BYTES,
                67_108_864L).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit
                                .ASSETS_AND_FETCH_UNIQUE_FONT_BYTES,
                        67_108_865L)
                .orElseThrow();
        assertEquals(EvaluationStage.ASSET_ADMISSION, problem.stage());
        assertEquals(ProblemCode.ASSET_BUDGET_EXCEEDED, problem.code());
        assertEquals("assetsAndFetch.uniqueFontBytes",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void manifestBytesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();

        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_MANIFEST_BYTES,
                4_194_303L).isEmpty());
        assertTrue(guard.admit(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_MANIFEST_BYTES,
                4_194_304L).isEmpty());

        var problem = guard.admit(
                        RenderingPipelineCapacityGuard.Limit
                                .ASSETS_AND_FETCH_MANIFEST_BYTES,
                        4_194_305L)
                .orElseThrow();
        assertEquals(EvaluationStage.ASSET_ADMISSION, problem.stage());
        assertEquals(ProblemCode.ASSET_BUDGET_EXCEEDED, problem.code());
        assertEquals("assetsAndFetch.manifestBytes",
                problem.limitId().orElseThrow().value());
    }

    @Test
    void staticCapabilitySourcesBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();
        var limit = RenderingPipelineCapacityGuard.Limit
                .CAPABILITY_RUNTIME_STATIC_CAPABILITY_SOURCES;

        assertTrue(guard.admit(limit, 4_095L).isEmpty());
        assertTrue(guard.admit(limit, 4_096L).isEmpty());

        var problem = guard.admit(limit, 4_097L)
                .orElseThrow();
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, problem.stage());
        assertEquals(ProblemCode.CAPABILITY_BUDGET_EXCEEDED, problem.code());
        assertEquals("capabilityRuntime.staticCapabilitySources",
                problem.limitId().orElseThrow().value());

        assertTrue(guard.admit(limit, 1, 1).isEmpty());
        assertEquals("capabilityRuntime.staticCapabilitySources",
                guard.admit(limit, 2, 1).orElseThrow()
                        .limitId().orElseThrow().value());
        assertThrows(IllegalArgumentException.class,
                () -> guard.admit(limit, 1, 4_097));
    }

    @Test
    void totalCapabilityDemandsBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();
        var limit = RenderingPipelineCapacityGuard.Limit
                .CAPABILITY_RUNTIME_TOTAL_DEMANDS;

        assertTrue(guard.admit(limit, 8_191L).isEmpty());
        assertTrue(guard.admit(limit, 8_192L).isEmpty());

        var problem = guard.admit(limit, 8_193L)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.CAPABILITY_BUDGET_EXCEEDED, problem.code());
        assertEquals("capabilityRuntime.totalDemands",
                problem.limitId().orElseThrow().value());

        assertTrue(guard.admit(limit, 1, 1).isEmpty());
        assertEquals("capabilityRuntime.totalDemands",
                guard.admit(limit, 2, 1).orElseThrow()
                        .limitId().orElseThrow().value());
        assertThrows(IllegalArgumentException.class,
                () -> guard.admit(limit, 1, 8_193));
    }

    @Test
    void clockCapabilityDemandsBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();
        var limit = RenderingPipelineCapacityGuard.Limit
                .CAPABILITY_RUNTIME_CLOCK_DEMANDS;

        assertTrue(guard.admit(limit, 4_095L).isEmpty());
        assertTrue(guard.admit(limit, 4_096L).isEmpty());

        var problem = guard.admit(limit, 4_097L)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.CAPABILITY_BUDGET_EXCEEDED, problem.code());
        assertEquals("capabilityRuntime.clockDemands",
                problem.limitId().orElseThrow().value());

        assertTrue(guard.admit(limit, 1, 1).isEmpty());
        assertEquals("capabilityRuntime.clockDemands",
                guard.admit(limit, 2, 1).orElseThrow()
                        .limitId().orElseThrow().value());
        assertThrows(IllegalArgumentException.class,
                () -> guard.admit(limit, 1, 4_097));
    }

    @Test
    void randomCapabilityDemandsBoundaryUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();
        var limit = RenderingPipelineCapacityGuard.Limit
                .CAPABILITY_RUNTIME_RANDOM_DEMANDS;

        assertTrue(guard.admit(limit, 4_095L).isEmpty());
        assertTrue(guard.admit(limit, 4_096L).isEmpty());

        var problem = guard.admit(limit, 4_097L)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.CAPABILITY_BUDGET_EXCEEDED, problem.code());
        assertEquals("capabilityRuntime.randomDemands",
                problem.limitId().orElseThrow().value());

        assertTrue(guard.admit(limit, 1, 1).isEmpty());
        assertEquals("capabilityRuntime.randomDemands",
                guard.admit(limit, 2, 1).orElseThrow()
                        .limitId().orElseThrow().value());
        assertThrows(IllegalArgumentException.class,
                () -> guard.admit(limit, 1, 4_097));
    }

    @Test
    void capabilityPositionBytesPerDemandUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();
        var limit = RenderingPipelineCapacityGuard.Limit
                .CAPABILITY_RUNTIME_POSITION_CANONICAL_BYTES_PER_DEMAND;

        assertTrue(guard.admit(limit, 2_047L).isEmpty());
        assertTrue(guard.admit(limit, 2_048L).isEmpty());

        var problem = guard.admit(limit, 2_049L)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.CAPABILITY_BUDGET_EXCEEDED, problem.code());
        assertEquals("capabilityRuntime.positionCanonicalBytesPerDemand",
                problem.limitId().orElseThrow().value());

        assertTrue(guard.admit(limit, 1, 1).isEmpty());
        assertEquals("capabilityRuntime.positionCanonicalBytesPerDemand",
                guard.admit(limit, 2, 1).orElseThrow()
                        .limitId().orElseThrow().value());
        assertThrows(IllegalArgumentException.class,
                () -> guard.admit(limit, 1, 2_049));
    }

    @Test
    void capabilityPositionBytesTotalUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();
        var limit = RenderingPipelineCapacityGuard.Limit
                .CAPABILITY_RUNTIME_POSITION_CANONICAL_BYTES_TOTAL;

        assertTrue(guard.admit(limit, 16_777_215L).isEmpty());
        assertTrue(guard.admit(limit, 16_777_216L).isEmpty());

        var problem = guard.admit(limit, 16_777_217L)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.CAPABILITY_BUDGET_EXCEEDED, problem.code());
        assertEquals("capabilityRuntime.positionCanonicalBytesTotal",
                problem.limitId().orElseThrow().value());

        assertTrue(guard.admit(limit, 1, 1).isEmpty());
        assertEquals("capabilityRuntime.positionCanonicalBytesTotal",
                guard.admit(limit, 2, 1).orElseThrow()
                        .limitId().orElseThrow().value());
        assertThrows(IllegalArgumentException.class,
                () -> guard.admit(limit, 1, 16_777_217));
    }

    @Test
    void capabilityStateRecordBytesUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();
        var limit = RenderingPipelineCapacityGuard.Limit
                .CAPABILITY_RUNTIME_CAPABILITY_STATE_RECORD_BYTES;

        assertTrue(guard.admit(limit, 1_048_575L).isEmpty());
        assertTrue(guard.admit(limit, 1_048_576L).isEmpty());

        var problem = guard.admit(limit, 1_048_577L)
                .orElseThrow();
        assertEquals(EvaluationStage.CAPABILITY_STATE, problem.stage());
        assertEquals(ProblemCode.CAPABILITY_BUDGET_EXCEEDED, problem.code());
        assertEquals("capabilityRuntime.capabilityStateRecordBytes",
                problem.limitId().orElseThrow().value());

        assertTrue(guard.admit(limit, 1, 1).isEmpty());
        assertEquals("capabilityRuntime.capabilityStateRecordBytes",
                guard.admit(limit, 2, 1).orElseThrow()
                        .limitId().orElseThrow().value());
        assertThrows(IllegalArgumentException.class,
                () -> guard.admit(limit, 1, 1_048_577));
    }

    @Test
    void capabilityResultDigestStreamingBytesUsesTheFrozenProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();
        var limit = RenderingPipelineCapacityGuard.Limit
                .CAPABILITY_RUNTIME_RESULT_DIGEST_STREAMING_BYTES;

        assertTrue(guard.admit(limit, 16_777_215L).isEmpty());
        assertTrue(guard.admit(limit, 16_777_216L).isEmpty());

        var problem = guard.admit(limit, 16_777_217L)
                .orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, problem.stage());
        assertEquals(ProblemCode.CAPABILITY_BUDGET_EXCEEDED, problem.code());
        assertEquals("capabilityRuntime.resultDigestStreamingBytes",
                problem.limitId().orElseThrow().value());

        assertTrue(guard.admit(limit, 1, 1).isEmpty());
        assertEquals("capabilityRuntime.resultDigestStreamingBytes",
                guard.admit(limit, 2, 1).orElseThrow()
                        .limitId().orElseThrow().value());
        assertThrows(IllegalArgumentException.class,
                () -> guard.admit(limit, 1, 16_777_217));
    }

    @Test
    void capabilityInitializationAttemptsUsesTheFrozenExactProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();
        var limit = RenderingPipelineCapacityGuard.Limit
                .CAPABILITY_RUNTIME_INITIALIZATION_ATTEMPTS;

        var below = guard.admit(limit, 2L).orElseThrow();
        assertEquals(EvaluationStage.CAPABILITY_STATE, below.stage());
        assertEquals(ProblemCode.CAPABILITY_STATE_UNAVAILABLE, below.code());
        assertEquals("capabilityRuntime.initializationAttempts",
                below.limitId().orElseThrow().value());
        assertTrue(guard.admit(limit, 3L).isEmpty());
        var above = guard.admit(limit, 4L).orElseThrow();
        assertEquals(EvaluationStage.CAPABILITY_STATE, above.stage());
        assertEquals(ProblemCode.CAPABILITY_STATE_UNAVAILABLE, above.code());
        assertEquals("capabilityRuntime.initializationAttempts",
                above.limitId().orElseThrow().value());

        assertEquals(3L, guard.exactValue(limit));
        assertThrows(IllegalArgumentException.class,
                () -> guard.maximumInclusive(limit));
        assertThrows(IllegalArgumentException.class,
                () -> guard.admit(limit, 3L, 3L));

        assertTrue(guard.admitRuntimeMaximum(limit, 1L).isEmpty());
        assertTrue(guard.admitRuntimeMaximum(limit, 2L).isEmpty());
        assertTrue(guard.admitRuntimeMaximum(limit, 3L).isEmpty());
        var fourth = guard.admitRuntimeMaximum(limit, 4L).orElseThrow();
        assertEquals(EvaluationStage.CAPABILITY_STATE, fourth.stage());
        assertEquals(ProblemCode.CAPABILITY_STATE_UNAVAILABLE, fourth.code());
        assertEquals("capabilityRuntime.initializationAttempts",
                fourth.limitId().orElseThrow().value());
    }

    @Test
    void capabilityRandomRejectionAttemptsUsesTheFrozenExactProductionGuardContract() {
        var guard = new RenderingPipelineCapacityGuard();
        var limit = RenderingPipelineCapacityGuard.Limit
                .CAPABILITY_RUNTIME_RANDOM_REJECTION_ATTEMPTS;

        var below = guard.admit(limit, 127L).orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, below.stage());
        assertEquals(ProblemCode.CAPABILITY_RESULT_INVALID, below.code());
        assertEquals("capabilityRuntime.randomRejectionAttempts",
                below.limitId().orElseThrow().value());
        assertTrue(guard.admit(limit, 128L).isEmpty());
        var above = guard.admit(limit, 129L).orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, above.stage());
        assertEquals(ProblemCode.CAPABILITY_RESULT_INVALID, above.code());
        assertEquals("capabilityRuntime.randomRejectionAttempts",
                above.limitId().orElseThrow().value());

        assertEquals(128L, guard.exactValue(limit));
        assertThrows(IllegalArgumentException.class,
                () -> guard.maximumInclusive(limit));
        assertThrows(IllegalArgumentException.class,
                () -> guard.admit(limit, 128L, 128L));

        assertTrue(guard.admitRuntimeMaximum(limit, 127L).isEmpty());
        assertTrue(guard.admitRuntimeMaximum(limit, 128L).isEmpty());
        var next = guard.admitRuntimeMaximum(limit, 129L).orElseThrow();
        assertEquals(EvaluationStage.MATERIALIZATION, next.stage());
        assertEquals(ProblemCode.CAPABILITY_RESULT_INVALID, next.code());
        assertEquals("capabilityRuntime.randomRejectionAttempts",
                next.limitId().orElseThrow().value());
    }

    @Test
    void totalDeadlineRequiresFrozenExactDurationAndEngineTaxonomy() {
        var guard = new RenderingPipelineCapacityGuard();
        var limit = RenderingPipelineCapacityGuard.Limit
                .DEADLINE_AND_RETENTION_TOTAL_DEADLINE_MILLIS;

        var below = guard.admit(limit, 59_999L).orElseThrow();
        assertEquals(EvaluationStage.ENGINE, below.stage());
        assertEquals(ProblemCode.RENDER_DEADLINE_EXCEEDED, below.code());
        assertEquals("deadlineAndRetention.totalDeadlineMillis",
                below.limitId().orElseThrow().value());
        assertTrue(guard.admit(limit, 60_000L).isEmpty());
        var above = guard.admit(limit, 60_001L).orElseThrow();
        assertEquals(EvaluationStage.ENGINE, above.stage());
        assertEquals(ProblemCode.RENDER_DEADLINE_EXCEEDED, above.code());
        assertEquals("deadlineAndRetention.totalDeadlineMillis",
                above.limitId().orElseThrow().value());

        assertEquals(60_000L, guard.exactValue(limit));
        assertThrows(IllegalArgumentException.class,
                () -> guard.maximumInclusive(limit));
        assertThrows(IllegalArgumentException.class,
                () -> guard.admit(limit, 60_000L, 60_000L));
    }

    @Test
    void admissionAndClosureDeadlineRequiresFrozenExactDurationAndClosureTaxonomy() {
        var guard = new RenderingPipelineCapacityGuard();
        var limit = RenderingPipelineCapacityGuard.Limit
                .DEADLINE_AND_RETENTION_ADMISSION_AND_CLOSURE_MILLIS;

        var below = guard.admit(limit, 4_999L).orElseThrow();
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, below.stage());
        assertEquals(ProblemCode.RENDER_DEADLINE_EXCEEDED, below.code());
        assertEquals("deadlineAndRetention.admissionAndClosureMillis",
                below.limitId().orElseThrow().value());
        assertTrue(guard.admit(limit, 5_000L).isEmpty());
        var above = guard.admit(limit, 5_001L).orElseThrow();
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, above.stage());
        assertEquals(ProblemCode.RENDER_DEADLINE_EXCEEDED, above.code());
        assertEquals("deadlineAndRetention.admissionAndClosureMillis",
                above.limitId().orElseThrow().value());

        assertEquals(5_000L, guard.exactValue(limit));
        assertThrows(IllegalArgumentException.class,
                () -> guard.maximumInclusive(limit));
        assertThrows(IllegalArgumentException.class,
                () -> guard.admit(limit, 5_000L, 5_000L));
    }
}
