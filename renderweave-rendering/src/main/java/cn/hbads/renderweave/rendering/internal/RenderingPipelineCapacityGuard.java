package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.LimitId;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;

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
                "closureAndExpansion.materializedStaticNodes", 20_000);

        private final String id;
        private final long maximumInclusive;

        Limit(String id, long maximumInclusive) {
            this.id = id;
            this.maximumInclusive = maximumInclusive;
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
                ProblemCode.EVALUATION_BUDGET_EXCEEDED,
                EvaluationStage.MATERIALIZATION,
                new LimitId(limit.id)));
    }
}
