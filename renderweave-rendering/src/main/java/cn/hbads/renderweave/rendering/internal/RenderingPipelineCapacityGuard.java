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
                EvaluationStage.DOCUMENT_SEAL);

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
    }
}
