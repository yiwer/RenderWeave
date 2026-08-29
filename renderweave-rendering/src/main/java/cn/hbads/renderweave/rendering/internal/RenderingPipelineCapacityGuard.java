package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.LimitId;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;

import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Frozen {@code renderweave-rendering-pipeline-capacity-guard/1.0} seam.
 *
 * <p>Authoritative counters derive an observed value at their frozen reservation point and pass
 * it through this single catalog. The isolated conformance fixture uses the same seam without
 * pretending that the full Evaluator or its counter source ran.</p>
 */
final class RenderingPipelineCapacityGuard {

    enum Limit {
        ACTUAL_TEMPLATE_INVOCATIONS(
                "closureAndExpansion.actualTemplateInvocations", 256),
        INVOCATION_DEPTH(
                "closureAndExpansion.invocationDepth", 16),
        COMPOSITION_VIEWPORTS(
                "closureAndExpansion.compositionViewports", 256),
        REPEAT_COLLECTION_ITEMS_PER_OCCURRENCE(
                "closureAndExpansion.repeatCollectionItemsPerOccurrence", 1_000),
        REPEAT_NESTING_DEPTH(
                "closureAndExpansion.repeatNestingDepth", 8),
        LOOP_FRAMES_TOTAL(
                "closureAndExpansion.loopFramesTotal", 10_000),
        RENDER_OCCURRENCES(
                "closureAndExpansion.renderOccurrences", 25_000),
        MATERIALIZED_STATIC_NODES(
                "closureAndExpansion.materializedStaticNodes", 20_000),
        GENERATED_TRACK_AND_CELL_ENTRIES(
                "closureAndExpansion.generatedTrackAndCellEntries", 100_000),
        LOGICAL_OPERATIONS(
                "closureAndExpansion.logicalOperations", 1_000_000),
        RENDER_DOCUMENT_CANONICAL_BYTES(
                "renderDocument.canonicalBytes",
                67_108_864,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        RENDER_DOCUMENT_JSON_DEPTH(
                "renderDocument.jsonDepth",
                128,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        RENDER_DOCUMENT_STATIC_NODES(
                "renderDocument.staticNodes",
                20_000,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        RENDER_DOCUMENT_CHILD_EDGES(
                "renderDocument.childEdges",
                19_999,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        RENDER_DOCUMENT_RUNS(
                "renderDocument.runs",
                10_000,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        RENDER_DOCUMENT_TEXT_SCALARS(
                "renderDocument.textScalars",
                1_000_000,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        RENDER_DOCUMENT_VECTOR_ENTRIES(
                "renderDocument.vectorEntries",
                100_000,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        DIAGNOSTICS_SIDECAR_ITEMS(
                "diagnostics.sidecarItems",
                25_000,
                ProblemCode.RENDER_DIAGNOSTIC_LIMIT_EXCEEDED,
                EvaluationStage.MATERIALIZATION),
        ASSETS_AND_FETCH_AUTHORED_ASSET_OCCURRENCES(
                "assetsAndFetch.authoredAssetOccurrences",
                4_096,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_UNIQUE_LOGICAL_ASSETS(
                "assetsAndFetch.uniqueLogicalAssets",
                512,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_ACTUAL_RESOLVE_OCCURRENCES(
                "assetsAndFetch.actualResolveOccurrences",
                2_048,
                ProblemCode.RESOURCE_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_RESOLUTION),
        ASSETS_AND_FETCH_RENDER_RESOURCE_ENTRIES(
                "assetsAndFetch.renderResourceEntries",
                2_048,
                ProblemCode.RESOURCE_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_RESOLUTION),
        ASSETS_AND_FETCH_UNIQUE_EXACT_CONTENTS(
                "assetsAndFetch.uniqueExactContents",
                128,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_OCCURRENCE_DECLARED_RAW_BYTES(
                "assetsAndFetch.occurrenceDeclaredRawBytes",
                2_147_483_648L,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_UNIQUE_RAW_BYTES(
                "assetsAndFetch.uniqueRawBytes",
                268_435_456L,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_OCCURRENCE_IMAGE_PIXELS(
                "assetsAndFetch.occurrenceImagePixels",
                1_000_000_000L,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_UNIQUE_IMAGE_PIXELS(
                "assetsAndFetch.uniqueImagePixels",
                125_000_000L,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_OCCURRENCE_FONT_BYTES(
                "assetsAndFetch.occurrenceFontBytes",
                536_870_912L,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_UNIQUE_FONT_BYTES(
                "assetsAndFetch.uniqueFontBytes",
                67_108_864L,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION);

        private final String id;
        private final long maximumInclusive;
        private final ProblemCode problemCode;
        private final EvaluationStage publicStage;

        Limit(String id, long maximumInclusive) {
            this(id, maximumInclusive,
                    ProblemCode.EVALUATION_BUDGET_EXCEEDED,
                    EvaluationStage.MATERIALIZATION);
        }

        Limit(
                String id,
                long maximumInclusive,
                ProblemCode problemCode,
                EvaluationStage publicStage
        ) {
            this.id = id;
            this.maximumInclusive = maximumInclusive;
            this.problemCode = problemCode;
            this.publicStage = publicStage;
        }
    }

    Optional<RenderingProblem> admit(Limit limit, long observedValue) {
        Objects.requireNonNull(limit, "limit");
        if (observedValue < 0) {
            throw new IllegalArgumentException("observedValue must be non-negative");
        }
        if (observedValue <= limit.maximumInclusive) {
            return Optional.empty();
        }
        return Optional.of(RenderingProblem.ofLimit(
                limit.problemCode,
                limit.publicStage,
                new LimitId(limit.id)));
    }

    RequestTracker newRequestTracker() {
        return new RequestTracker(this);
    }

    /** Request-local atomic accumulator backed by the same frozen limit catalog. */
    static final class RequestTracker {
        private final RenderingPipelineCapacityGuard guard;
        private final EnumMap<Limit, Long> observed = new EnumMap<>(Limit.class);

        private RequestTracker(RenderingPipelineCapacityGuard guard) {
            this.guard = guard;
        }

        Optional<RenderingProblem> reserve(Limit limit, long delta) {
            Objects.requireNonNull(limit, "limit");
            if (delta < 0) {
                throw new IllegalArgumentException("delta must be non-negative");
            }
            var current = observed.getOrDefault(limit, 0L);
            var next = delta > Long.MAX_VALUE - current
                    ? Long.MAX_VALUE
                    : current + delta;
            var problem = guard.admit(limit, next);
            if (problem.isEmpty()) {
                observed.put(limit, next);
            }
            return problem;
        }

        Optional<RenderingProblem> observeMaximum(Limit limit, long observedValue) {
            Objects.requireNonNull(limit, "limit");
            if (observedValue < 0) {
                throw new IllegalArgumentException("observedValue must be non-negative");
            }
            var maximum = Math.max(observed.getOrDefault(limit, 0L), observedValue);
            var problem = guard.admit(limit, maximum);
            if (problem.isEmpty()) {
                observed.put(limit, maximum);
            }
            return problem;
        }
    }
}
